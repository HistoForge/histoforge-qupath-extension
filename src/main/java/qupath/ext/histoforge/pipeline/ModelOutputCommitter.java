package qupath.ext.histoforge.pipeline;

import java.time.Instant;
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
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.HierarchyPolicy;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Sole owner that stages and applies model-output deltas to one disposable working image. */
public final class ModelOutputCommitter {

    private final ImageData<?> workingImage;
    private final ExecutionLimitsCodec.ClientExecutionLimits limits;
    private OutputIndex outputIndex;
    private GenerationStoreCodec.GenerationStore generationStore;
    private long workingEpoch;
    private boolean poisoned;

    /** Retain one separately loaded working image; model-only state is captured on first use. */
    public ModelOutputCommitter(
            ImageData<?> workingImage, ExecutionLimitsCodec.ClientExecutionLimits limits) {
        this.workingImage = Objects.requireNonNull(workingImage, "workingImage");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Complete action and evidence facts needed to stage one target-region transaction.
     *
     * <p>{@code prospectiveCommittedAt} enters persisted evidence only if the separate working
     * image later saves successfully; a failed apply/save discards the value with that image.
     */
    public record StagingRequest(
            // User action whose one target transaction is being staged.
            String actionId,
            // Captured action preference governing same-class unattributed overlap suppression.
            boolean removeOverlappingSameClassDuplicates,
            // Exact client build and limits captured for the action.
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            // Gateway deployment captured with the executable module snapshot.
            String deploymentId,
            // Current logical outputs to replace; empty for Fill additions.
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            // Complete validated model generations produced by this target transaction.
            List<CandidateGeneration> generations,
            // Class-only changes for preserved current objects produced by staged scripts.
            List<HierarchyDelta.ClassChange> classChanges,
            // Millisecond UTC timestamp used if the staged generation reaches persistence.
            Instant prospectiveCommittedAt) {
        public StagingRequest {
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            clientBuild = Objects.requireNonNull(clientBuild, "client_build");
            deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
            replacementClosure = immutableSet(replacementClosure, "replacement_closure");
            generations = immutableList(generations, "generations");
            classChanges = immutableList(classChanges, "class_changes");
            prospectiveCommittedAt =
                    Objects.requireNonNull(prospectiveCommittedAt, "prospective_committed_at");
            CanonicalCodec.rfc3339Millis(prospectiveCommittedAt, "prospective_committed_at");
        }
    }

    /** Exact retained object whose provenance references a missing generation record. */
    public record MissingGenerationReference(
            ExecutionScopeCodec.OutputKey output, UUID objectId, String generationId) {
        public MissingGenerationReference {
            output = Objects.requireNonNull(output, "output");
            objectId = Objects.requireNonNull(objectId, "object_id");
            generationId =
                    CanonicalCodec.requireCanonicalUuid(generationId, "output_generation_id");
        }
    }

    /** Counts and exact audit warnings from one successful working-hierarchy application. */
    public record ApplyResult(
            // New process-local working epoch after this delta.
            long workingEpoch,
            // Current attributed objects removed by exact replacement ownership.
            int removedObjects,
            // New attributed model objects added.
            int addedObjects,
            // Preserved current objects reclassified by a staged script.
            int changedClasses,
            // Current preserved objects whose explicit parent link changed.
            int reparentedObjects,
            // Retained attributed objects whose generation record is missing from the store.
            List<MissingGenerationReference> missingGenerationReferences,
            // Whether hierarchy or generation state actually changed.
            boolean changed) {
        public ApplyResult {
            workingEpoch =
                    CanonicalCodec.requireNonnegativeSafeInteger(workingEpoch, "working_epoch");
            removedObjects =
                    CanonicalCodec.requireNonnegativeInt32(removedObjects, "removed_objects");
            addedObjects = CanonicalCodec.requireNonnegativeInt32(addedObjects, "added_objects");
            changedClasses =
                    CanonicalCodec.requireNonnegativeInt32(changedClasses, "changed_classes");
            reparentedObjects =
                    CanonicalCodec.requireNonnegativeInt32(reparentedObjects, "reparented_objects");
            missingGenerationReferences =
                    List.copyOf(
                            Objects.requireNonNull(
                                    missingGenerationReferences, "missing_generation_references"));
        }
    }

    /** Build one complete delta without mutating the working image or its derived index. */
    public synchronized HierarchyDelta stage(StagingRequest request) {
        requireUsable();
        ensureModelState();
        return ModelOutputStager.stage(
                Objects.requireNonNull(request, "request"),
                workingImage.getHierarchy(),
                outputIndex,
                limits,
                workingEpoch);
    }

    /**
     * Revalidate and apply one complete delta, then publish one hierarchy event.
     *
     * <p>The caller must own the working image. A failure after the first mutation poisons this
     * committer; the caller must discard the separate working {@code ImageData} rather than retry
     * or save it.
     */
    public synchronized ApplyResult apply(HierarchyDelta delta) {
        requireUsable();
        ensureModelState();
        HierarchyDelta value = Objects.requireNonNull(delta, "delta");
        if (value.baseWorkingEpoch() != workingEpoch) {
            throw new IllegalArgumentException("hierarchy delta was staged from a stale epoch");
        }

        PathObjectHierarchy hierarchy = workingImage.getHierarchy();
        Map<UUID, PathObject> currentObjects = currentObjects(hierarchy);
        var provenanceReader = new ProvenanceCodec.MetadataReader();
        validateReplacementClosure(value, currentObjects.values(), provenanceReader);
        Map<UUID, PathObject> additions = constructAdditions(value.changes().addObjects());
        validateClassChanges(value.changes().classChanges(), currentObjects);
        validateModelParents(value, currentObjects, additions);
        HierarchyPolicy.requireAcyclicAssignments(
                currentObjects.values(),
                value.changes().removeObjectIds(),
                value.changes().addObjects(),
                value.changes().parentAssignments());

        GenerationStoreCodec.GenerationStore currentPersisted =
                ImageDataStateCodec.readGenerationStore(workingImage)
                        .orElseGet(GenerationStoreCodec::emptyStore);
        if (!generationStore.equals(currentPersisted)) {
            throw new IllegalArgumentException(
                    "working generation store changed outside its committer");
        }
        GenerationStoreCodec.GenerationStore merged =
                GenerationStoreCodec.merge(List.of(generationStore, value.stagedGenerationStore()));
        Set<ProvenanceCodec.ObjectProvenance> postProvenance =
                postDeltaProvenance(value, currentObjects.values(), provenanceReader);
        GenerationStoreCodec.SweepResult sweep =
                GenerationStoreCodec.markAndSweep(merged, postProvenance);
        List<MissingGenerationReference> missingGenerationReferences =
                missingGenerationReferences(
                        currentObjects,
                        additions,
                        value.changes().removeObjectIds(),
                        sweep.missingGenerationRecordIds(),
                        provenanceReader);
        String encodedGenerationStore =
                ImageDataStateCodec.encodeGenerationStore(sweep.store(), limits);

        boolean storeChanges = !generationStore.equals(sweep.store());
        Map<PathObject, String> prospectiveClassChanges = new LinkedHashMap<>();
        for (HierarchyDelta.ClassChange change : value.changes().classChanges()) {
            prospectiveClassChanges.put(currentObjects.get(change.objectId()), change.newClass());
        }
        List<PathObject> prospectiveRemovals =
                value.changes().removeObjectIds().stream().map(currentObjects::get).toList();
        ImageDataRevisionDigester.requireProspectiveHierarchyFits(
                workingImage,
                prospectiveRemovals,
                prospectiveClassChanges,
                additions.values(),
                storeChanges ? Optional.of(encodedGenerationStore) : Optional.empty(),
                limits);

        boolean hierarchyChanges =
                !value.changes().removeObjectIds().isEmpty()
                        || !value.changes().classChanges().isEmpty()
                        || !value.changes().addObjects().isEmpty()
                        || !value.changes().parentAssignments().stream()
                                .filter(assignment -> !additions.containsKey(assignment.objectId()))
                                .toList()
                                .isEmpty();
        boolean changed = hierarchyChanges || storeChanges;
        if (!changed) {
            return new ApplyResult(workingEpoch, 0, 0, 0, 0, missingGenerationReferences, false);
        }

        int reparentedObjects =
                (int)
                        value.changes().parentAssignments().stream()
                                .filter(assignment -> !additions.containsKey(assignment.objectId()))
                                .count();
        boolean mutationStarted = false;
        try {
            mutationStarted = true;
            for (HierarchyDelta.ClassChange change : value.changes().classChanges()) {
                currentObjects
                        .get(change.objectId())
                        .setPathClass(PathClass.fromString(change.newClass()));
            }

            PathObject root = hierarchy.getRootObject();
            for (PathObject addition : additions.values()) {
                root.addChildObject(addition);
            }
            applyParentAssignments(
                    value.changes().parentAssignments(), currentObjects, additions, root);

            List<PathObject> removals =
                    value.changes().removeObjectIds().stream()
                            .map(currentObjects::get)
                            .sorted(Comparator.comparingInt(ModelOutputCommitter::depth).reversed())
                            .toList();
            for (PathObject removal : removals) {
                PathObject parent = removal.getParent();
                if (parent != null) {
                    parent.removeChildObject(removal);
                }
            }
            hierarchy.getSelectionModel().deselectObjects(removals);

            if (storeChanges) {
                ImageDataStateCodec.writeGenerationStore(workingImage, sweep.store(), limits);
            }

            for (UUID removal : value.changes().removeObjectIds()) {
                outputIndex.remove(removal);
            }
            outputIndex.addAll(additions.values());
            generationStore = sweep.store();
            workingEpoch = Math.incrementExact(workingEpoch);
            hierarchy.fireHierarchyChangedEvent(this);
            return new ApplyResult(
                    workingEpoch,
                    value.changes().removeObjectIds().size(),
                    additions.size(),
                    value.changes().classChanges().size(),
                    reparentedObjects,
                    missingGenerationReferences,
                    true);
        } catch (RuntimeException | Error failure) {
            if (mutationStarted) {
                poisoned = true;
            }
            throw failure;
        }
    }

    /** Current process-local hierarchy epoch for staging the next deterministic transaction. */
    public synchronized long workingEpoch() {
        requireUsable();
        return workingEpoch;
    }

    /**
     * Apply only successful metric parent-normalization edges to the disposable working image.
     *
     * <p>This path deliberately does not merge or sweep generation evidence: Metrics owns only
     * derived parent links. Every UUID is re-resolved and the complete assignment graph is checked
     * before the first mutation. A failure after mutation poisons this committer and requires the
     * caller to discard the working image.
     */
    public synchronized ApplyResult applyParentNormalization(
            List<HierarchyDelta.ParentAssignment> assignments) {
        requireUsable();
        List<HierarchyDelta.ParentAssignment> values =
                List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        if (values.isEmpty()) {
            return new ApplyResult(workingEpoch, 0, 0, 0, 0, List.of(), false);
        }

        PathObjectHierarchy hierarchy = workingImage.getHierarchy();
        Map<UUID, PathObject> currentObjects = currentObjects(hierarchy);
        Set<UUID> children = new LinkedHashSet<>();
        for (HierarchyDelta.ParentAssignment assignment : values) {
            if (!children.add(assignment.objectId())) {
                throw new IllegalArgumentException(
                        "parent-normalization child UUIDs must be unique");
            }
            if (!currentObjects.containsKey(assignment.objectId())) {
                throw new IllegalArgumentException(
                        "parent-normalization child is no longer present");
            }
            if (assignment
                    .parentObjectId()
                    .filter(id -> !currentObjects.containsKey(id))
                    .isPresent()) {
                throw new IllegalArgumentException(
                        "parent-normalization parent is no longer present");
            }
        }
        HierarchyPolicy.requireAcyclicAssignments(
                currentObjects.values(), Set.of(), List.of(), values);

        PathObject root = hierarchy.getRootObject();
        List<HierarchyDelta.ParentAssignment> changes =
                values.stream()
                        .filter(
                                assignment -> {
                                    PathObject child = currentObjects.get(assignment.objectId());
                                    PathObject parent =
                                            assignment
                                                    .parentObjectId()
                                                    .map(currentObjects::get)
                                                    .orElse(root);
                                    return child.getParent() != parent;
                                })
                        .toList();
        if (changes.isEmpty()) {
            return new ApplyResult(workingEpoch, 0, 0, 0, 0, List.of(), false);
        }

        try {
            applyParentAssignments(changes, currentObjects, Map.of(), root);
            workingEpoch = Math.incrementExact(workingEpoch);
            hierarchy.fireHierarchyChangedEvent(this);
            return new ApplyResult(workingEpoch, 0, 0, 0, changes.size(), List.of(), true);
        } catch (RuntimeException | Error failure) {
            poisoned = true;
            throw failure;
        }
    }

    private void validateReplacementClosure(
            HierarchyDelta delta,
            Collection<PathObject> currentObjects,
            ProvenanceCodec.MetadataReader provenanceReader) {
        Set<UUID> actual = new LinkedHashSet<>();
        for (PathObject object : currentObjects) {
            Optional<ProvenanceCodec.ObjectProvenance> provenance =
                    object.hasMetadata()
                            ? provenanceReader.read(object.getMetadata())
                            : Optional.empty();
            if (provenance.isPresent()
                    && delta.replacementClosure().contains(provenance.orElseThrow().output())) {
                actual.add(Objects.requireNonNull(object.getID(), "persistent object UUID"));
            }
        }
        if (!actual.equals(delta.changes().removeObjectIds())) {
            throw new IllegalArgumentException(
                    "replacement closure no longer matches the current attributed objects");
        }
    }

    private static void validateClassChanges(
            List<HierarchyDelta.ClassChange> changes, Map<UUID, PathObject> currentObjects) {
        for (HierarchyDelta.ClassChange change : changes) {
            PathObject object = currentObjects.get(change.objectId());
            if (object == null) {
                throw new IllegalArgumentException("class-change object is no longer present");
            }
            String current =
                    object.getPathClass() == null ? null : object.getPathClass().toString();
            if (!change.expectedOldClass().equals(current)) {
                throw new IllegalArgumentException(
                        "class-change precondition no longer matches the current object");
            }
        }
    }

    private static void validateModelParents(
            HierarchyDelta delta,
            Map<UUID, PathObject> currentObjects,
            Map<UUID, PathObject> additions) {
        Map<UUID, Optional<UUID>> byChild = new LinkedHashMap<>();
        for (HierarchyDelta.ParentAssignment assignment : delta.changes().parentAssignments()) {
            byChild.put(assignment.objectId(), assignment.parentObjectId());
        }
        Set<String> checkedGenerations = new LinkedHashSet<>();
        for (HierarchyDelta.ObjectDraft draft : delta.changes().addObjects()) {
            GenerationEvidenceCodec.GenerationRecord record =
                    delta.stagedGenerationStore()
                            .currentGenerationRecords()
                            .get(draft.provenance().outputGenerationId());
            ExecutionScopeCodec.ExecutionScope scope = draft.provenance().output().scope();
            Optional<UUID> expected = HierarchyPolicy.expectedParent(scope);
            if (!Objects.equals(expected, byChild.get(draft.objectId()))) {
                throw new IllegalArgumentException(
                        "added model object parent does not match its exact execution scope");
            }
            // The working hierarchy cannot change during apply. All children of this generation
            // share one captured target and source parent; validate that binding once per apply.
            // The immediate-parent assignment above must still be checked for every child.
            if (!checkedGenerations.add(draft.provenance().outputGenerationId())) {
                continue;
            }
            ExecutionScopeCodec.TargetRegion target =
                    delta.stagedGenerationStore()
                            .targetRegions()
                            .get(record.inputEvidence().targetRegionDigest());
            if (target.key() instanceof ExecutionScopeCodec.Roi roiKey) {
                PathObject roi = currentObjects.get(UUID.fromString(roiKey.roiId()));
                if (!qupath.ext.histoforge.roi.RoiRegistry.isAdministrativeRoi(roi)
                        && !qupath.ext.histoforge.roi.RoiRegistry.isOrdinaryAreaAnnotation(roi)) {
                    throw new IllegalArgumentException(
                            "ROI-scoped model output requires its exact area annotation");
                }
                requireGeometryBinding(roi, target.geometryDigest(), target, "target ROI");
            }
            if (scope instanceof ExecutionScopeCodec.ParentScope) {
                UUID parentId = expected.orElseThrow();
                PathObject parent =
                        Optional.ofNullable(additions.get(parentId))
                                .orElseGet(() -> currentObjects.get(parentId));
                requireGeometryBinding(
                        parent,
                        record.inputEvidence().parentGeometryDigest().orElseThrow(),
                        target,
                        "source parent");
                String acceptanceDigest =
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                new ExecutionScopeCodec.ScopeAcceptance(
                                        target,
                                        Optional.of(NativeRoiGeometry.convert(parent.getROI()))));
                if (!record.inputEvidence().scopeAcceptanceDigest().equals(acceptanceDigest)) {
                    throw new IllegalArgumentException(
                            "source parent geometry does not match staged scope acceptance");
                }
            }
        }
    }

    private static void requireGeometryBinding(
            PathObject object,
            String expectedGeometryDigest,
            ExecutionScopeCodec.TargetRegion target,
            String path) {
        if (object == null || object.getROI() == null || !object.getROI().isArea()) {
            throw new IllegalArgumentException(path + " must be a current polygonal area object");
        }
        if (!expectedGeometryDigest.equals(
                CanonicalGeometryCodec.canonicalDigest(
                        NativeRoiGeometry.convert(object.getROI())))) {
            throw new IllegalArgumentException(path + " geometry changed after input planning");
        }
        if (object.getROI().getZ() != target.plane().z()
                || object.getROI().getT() != target.plane().t()) {
            throw new IllegalArgumentException(path + " plane changed after input planning");
        }
    }

    private static Map<UUID, PathObject> constructAdditions(
            List<HierarchyDelta.ObjectDraft> drafts) {
        Map<UUID, PathObject> result = new LinkedHashMap<>();
        Map<ProvenanceCodec.ObjectProvenance, Map<String, String>> metadataByProvenance =
                new LinkedHashMap<>();
        for (HierarchyDelta.ObjectDraft draft : drafts) {
            PathObject object =
                    PathObjects.createDetectionObject(
                            draft.roiSlide(), PathClass.fromString(draft.pathClass()));
            object.setID(draft.objectId());
            Map<String, String> metadata =
                    metadataByProvenance.computeIfAbsent(
                            draft.provenance(),
                            provenance -> {
                                Map<String, String> encoded = new LinkedHashMap<>();
                                ProvenanceCodec.writeMetadata(encoded, provenance);
                                return encoded;
                            });
            // Each native object owns its map; equal provenance shares only immutable text.
            object.getMetadata().putAll(metadata);
            if (result.putIfAbsent(draft.objectId(), object) != null) {
                throw new IllegalArgumentException("added object UUIDs must be unique");
            }
        }
        return result;
    }

    private static Set<ProvenanceCodec.ObjectProvenance> postDeltaProvenance(
            HierarchyDelta delta,
            Collection<PathObject> currentObjects,
            ProvenanceCodec.MetadataReader provenanceReader) {
        // Sweep needs every distinct output/generation pair, not one entry per object. Do not
        // deduplicate by generation ID: a second output under that ID must still fail validation.
        Set<ProvenanceCodec.ObjectProvenance> result = new LinkedHashSet<>();
        for (PathObject object : currentObjects) {
            if (delta.changes().removeObjectIds().contains(object.getID())
                    || !object.hasMetadata()) {
                continue;
            }
            provenanceReader.read(object.getMetadata()).ifPresent(result::add);
        }
        delta.changes().addObjects().stream()
                .map(HierarchyDelta.ObjectDraft::provenance)
                .forEach(result::add);
        return result;
    }

    private static List<MissingGenerationReference> missingGenerationReferences(
            Map<UUID, PathObject> currentObjects,
            Map<UUID, PathObject> additions,
            Set<UUID> removals,
            Set<String> missingGenerationIds,
            ProvenanceCodec.MetadataReader provenanceReader) {
        if (missingGenerationIds.isEmpty()) {
            return List.of();
        }
        List<MissingGenerationReference> result = new ArrayList<>();
        currentObjects.forEach(
                (objectId, object) -> {
                    if (!removals.contains(objectId)) {
                        missingGenerationReference(
                                        objectId, object, missingGenerationIds, provenanceReader)
                                .ifPresent(result::add);
                    }
                });
        additions.forEach(
                (objectId, object) ->
                        missingGenerationReference(
                                        objectId, object, missingGenerationIds, provenanceReader)
                                .ifPresent(result::add));
        result.sort(Comparator.comparing(MissingGenerationReference::objectId));
        return List.copyOf(result);
    }

    private static Optional<MissingGenerationReference> missingGenerationReference(
            UUID objectId,
            PathObject object,
            Set<String> missingGenerationIds,
            ProvenanceCodec.MetadataReader provenanceReader) {
        if (!object.hasMetadata()) {
            return Optional.empty();
        }
        Optional<ProvenanceCodec.ObjectProvenance> provenance =
                provenanceReader.read(object.getMetadata());
        if (provenance.isEmpty()
                || !missingGenerationIds.contains(provenance.orElseThrow().outputGenerationId())) {
            return Optional.empty();
        }
        ProvenanceCodec.ObjectProvenance value = provenance.orElseThrow();
        return Optional.of(
                new MissingGenerationReference(
                        value.output(), objectId, value.outputGenerationId()));
    }

    private static void applyParentAssignments(
            List<HierarchyDelta.ParentAssignment> assignments,
            Map<UUID, PathObject> current,
            Map<UUID, PathObject> additions,
            PathObject root) {
        for (HierarchyDelta.ParentAssignment assignment : assignments) {
            PathObject child =
                    Optional.ofNullable(additions.get(assignment.objectId()))
                            .orElseGet(() -> current.get(assignment.objectId()));
            PathObject parent =
                    assignment
                            .parentObjectId()
                            .map(
                                    id ->
                                            Optional.ofNullable(additions.get(id))
                                                    .orElseGet(() -> current.get(id)))
                            .orElse(root);
            if (child.getParent() != parent) {
                parent.addChildObject(child);
            }
        }
    }

    private static Map<UUID, PathObject> currentObjects(PathObjectHierarchy hierarchy) {
        Map<UUID, PathObject> result = new LinkedHashMap<>();
        for (PathObject object : hierarchy.getAllObjects(false)) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            if (result.putIfAbsent(objectId, object) != null) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate object UUID " + objectId);
            }
        }
        return result;
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

    private void requireUsable() {
        if (poisoned) {
            throw new IllegalStateException(
                    "working hierarchy must be discarded after a failed mutation");
        }
    }

    private void ensureModelState() {
        if (outputIndex != null) {
            return;
        }
        outputIndex = OutputIndex.fromHierarchy(workingImage.getHierarchy());
        generationStore =
                ImageDataStateCodec.readGenerationStore(workingImage)
                        .orElseGet(GenerationStoreCodec::emptyStore);
    }

    private static <T> Set<T> immutableSet(Set<T> values, String path) {
        if (values == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        Set<T> result = new LinkedHashSet<>();
        for (T value : values) {
            result.add(Objects.requireNonNull(value, path + " item"));
        }
        return Set.copyOf(result);
    }

    private static <T> List<T> immutableList(List<T> values, String path) {
        if (values == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        List<T> result = new ArrayList<>(values.size());
        for (T value : values) {
            result.add(Objects.requireNonNull(value, path + " item"));
        }
        return List.copyOf(result);
    }
}
