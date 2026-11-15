package qupath.ext.histoforge.spatial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.lib.roi.interfaces.ROI;

/** Complete hierarchy and generation-evidence change staged for one transaction. */
public record HierarchyDelta(
        // User action whose target transaction produced this delta.
        String actionId,
        // Exact logical outputs whose current attributed objects are replaced by this delta.
        Set<ExecutionScopeCodec.OutputKey> replacementClosure,
        // Working-hierarchy epoch against which all staged assumptions were captured.
        long baseWorkingEpoch,
        // Concrete removals, existing-object class changes, additions, and parent assignments.
        ChangeSet changes,
        // Normalized evidence fragments for every nonempty model generation added by the delta.
        GenerationStoreCodec.GenerationStore stagedGenerationStore) {

    public HierarchyDelta {
        actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
        replacementClosure = immutableSet(replacementClosure, "replacement_closure");
        baseWorkingEpoch =
                CanonicalCodec.requireNonnegativeSafeInteger(
                        baseWorkingEpoch, "base_working_epoch");
        changes = Objects.requireNonNull(changes, "changes");
        stagedGenerationStore =
                Objects.requireNonNull(stagedGenerationStore, "staged_generation_store");
        validateGenerationEvidence(actionId, changes.addObjects(), stagedGenerationStore);
        if (!changes.removeObjectIds().isEmpty() && replacementClosure.isEmpty()) {
            throw new IllegalArgumentException(
                    "hierarchy removals require a nonempty replacement closure");
        }
    }

    /** Existing-object class replacement guarded by the exact class observed during staging. */
    public record ClassChange(
            // Persistent UUID of the existing object to reclassify.
            UUID objectId,
            // Exact class that must still be present when the delta applies.
            String expectedOldClass,
            // Exact class assigned when the precondition remains true.
            String newClass) {
        public ClassChange {
            objectId = Objects.requireNonNull(objectId, "object_id");
            expectedOldClass =
                    CanonicalCodec.requirePathClassName(expectedOldClass, "expected_old_class");
            newClass = CanonicalCodec.requirePathClassName(newClass, "new_class");
            if (expectedOldClass.equals(newClass)) {
                throw new IllegalArgumentException("class changes must change the class");
            }
        }
    }

    /**
     * New attributed model object whose persistent identity is fixed before QuPath construction.
     */
    public static final class ObjectDraft {
        private final UUID objectId;
        private final ROI roiSlide;
        private final String pathClass;
        private final SourceIdentityCodec.ImagePlaneIndex plane;
        private final ProvenanceCodec.ObjectProvenance provenance;

        /** Validate an arbitrary native outline before staging it. */
        public ObjectDraft(
                UUID objectId,
                ROI roiSlide,
                String pathClass,
                SourceIdentityCodec.ImagePlaneIndex plane,
                ProvenanceCodec.ObjectProvenance provenance) {
            this.objectId = Objects.requireNonNull(objectId, "object_id");
            this.roiSlide = SourceInputGeometry.validatedPredictionRoi(roiSlide, plane);
            this.pathClass = CanonicalCodec.requirePathClassName(pathClass, "path_class");
            this.plane = Objects.requireNonNull(plane, "plane");
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        /** Share an accepted candidate's validated outline; scripts never receive this ROI. */
        public static ObjectDraft fromCandidate(
                CandidateGeneration.SlideCandidate candidate,
                ProvenanceCodec.ObjectProvenance provenance) {
            return new ObjectDraft(Objects.requireNonNull(candidate, "candidate"), provenance);
        }

        private ObjectDraft(
                CandidateGeneration.SlideCandidate candidate,
                ProvenanceCodec.ObjectProvenance provenance) {
            objectId = UUID.fromString(candidate.candidateId());
            roiSlide = candidate.roiSlide();
            pathClass = candidate.pathClass();
            plane = candidate.plane();
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        public UUID objectId() {
            return objectId;
        }

        public ROI roiSlide() {
            return roiSlide;
        }

        public String pathClass() {
            return pathClass;
        }

        public SourceIdentityCodec.ImagePlaneIndex plane() {
            return plane;
        }

        public ProvenanceCodec.ObjectProvenance provenance() {
            return provenance;
        }

        /** Detached geometry for callers that need spatial operations on the outline. */
        public Geometry geometrySlide() {
            return NativeRoiGeometry.convert(roiSlide).copy();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ObjectDraft draft
                    && objectId.equals(draft.objectId)
                    && roiSlide.equals(draft.roiSlide)
                    && pathClass.equals(draft.pathClass)
                    && plane.equals(draft.plane)
                    && provenance.equals(draft.provenance);
        }

        @Override
        public int hashCode() {
            int hash = objectId.hashCode();
            hash = 31 * hash + roiSlide.hashCode();
            hash = 31 * hash + pathClass.hashCode();
            hash = 31 * hash + plane.hashCode();
            return 31 * hash + provenance.hashCode();
        }

        @Override
        public String toString() {
            return "ObjectDraft[objectId="
                    + objectId
                    + ", roiSlide="
                    + roiSlide
                    + ", pathClass="
                    + pathClass
                    + ", plane="
                    + plane
                    + ", provenance="
                    + provenance
                    + "]";
        }
    }

    /** Explicit parent link for a new object or a preserved object lifted from removed output. */
    public record ParentAssignment(
            // Persistent UUID of the child whose parent is assigned.
            UUID objectId,
            // Persistent parent UUID; empty selects the QuPath hierarchy root.
            Optional<UUID> parentObjectId) {
        public ParentAssignment {
            objectId = Objects.requireNonNull(objectId, "object_id");
            parentObjectId = Objects.requireNonNull(parentObjectId, "parent_object_id");
            if (parentObjectId.filter(objectId::equals).isPresent()) {
                throw new IllegalArgumentException("an object cannot be its own parent");
            }
        }
    }

    /** Concrete changes that apply together to one disposable working hierarchy. */
    public record ChangeSet(
            // Persistent UUIDs of current attributed objects selected for exact replacement.
            Set<UUID> removeObjectIds,
            // Class-only changes to current preserved objects.
            List<ClassChange> classChanges,
            // New attributed model objects to construct.
            List<ObjectDraft> addObjects,
            // Explicit parent links for every addition and every preserved descendant lift.
            List<ParentAssignment> parentAssignments) {
        public ChangeSet {
            removeObjectIds = immutableSet(removeObjectIds, "remove_object_ids");
            classChanges = immutableList(classChanges, "class_changes");
            addObjects = immutableList(addObjects, "add_objects");
            parentAssignments = immutableList(parentAssignments, "parent_assignments");

            Set<UUID> changedClasses = uniqueIds(classChanges, ClassChange::objectId, "class");
            Set<UUID> additions = uniqueIds(addObjects, ObjectDraft::objectId, "addition");
            Set<UUID> assigned =
                    uniqueIds(parentAssignments, ParentAssignment::objectId, "parent assignment");
            requireDisjoint(removeObjectIds, changedClasses, "removed and class-changed");
            requireDisjoint(removeObjectIds, additions, "removed and added");
            requireDisjoint(removeObjectIds, assigned, "removed and parent-assigned");
            requireDisjoint(changedClasses, additions, "class-changed and added");
            if (!assigned.containsAll(additions)) {
                throw new IllegalArgumentException(
                        "every added object requires one explicit parent assignment");
            }
        }
    }

    private static void validateGenerationEvidence(
            String actionId,
            List<ObjectDraft> drafts,
            GenerationStoreCodec.GenerationStore stagedStore) {
        Map<String, Integer> draftCounts = new HashMap<>();
        for (ObjectDraft draft : drafts) {
            ProvenanceCodec.ObjectProvenance provenance = draft.provenance();
            GenerationEvidenceCodec.GenerationRecord record =
                    stagedStore.currentGenerationRecords().get(provenance.outputGenerationId());
            if (record == null) {
                throw new IllegalArgumentException(
                        "added object provenance has no staged generation record");
            }
            if (!record.output().equals(provenance.output())) {
                throw new IllegalArgumentException(
                        "added object provenance does not match its generation record");
            }
            ExecutionScopeCodec.TargetRegion target =
                    stagedStore.targetRegions().get(record.inputEvidence().targetRegionDigest());
            if (target == null || !draft.plane().equals(target.plane())) {
                throw new IllegalArgumentException(
                        "added object plane does not match its staged target region");
            }
            draftCounts.merge(provenance.outputGenerationId(), 1, Math::addExact);
        }
        for (GenerationEvidenceCodec.GenerationRecord record :
                stagedStore.currentGenerationRecords().values()) {
            if (!actionId.equals(record.actionId())) {
                throw new IllegalArgumentException(
                        "staged generation record belongs to another action");
            }
            int actual = draftCounts.getOrDefault(record.outputGenerationId(), 0);
            if (record.counts().committedObjects() != actual) {
                throw new IllegalArgumentException(
                        "staged generation count does not match added objects");
            }
        }
        if (draftCounts.size() != stagedStore.currentGenerationRecords().size()) {
            throw new IllegalArgumentException(
                    "staged generation records and object provenance must have exact membership");
        }
    }

    private static <T> Set<T> immutableSet(Set<T> values, String path) {
        if (values == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T value : values) {
            if (!result.add(Objects.requireNonNull(value, path + " item"))) {
                throw new IllegalArgumentException(path + " contains a duplicate");
            }
        }
        return Collections.unmodifiableSet(result);
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

    private static <T> Set<UUID> uniqueIds(
            List<T> values, java.util.function.Function<T, UUID> id, String path) {
        Map<UUID, T> result = new LinkedHashMap<>();
        for (T value : values) {
            UUID objectId = id.apply(value);
            if (result.putIfAbsent(objectId, value) != null) {
                throw new IllegalArgumentException(path + " object IDs must be unique");
            }
        }
        return Set.copyOf(result.keySet());
    }

    private static void requireDisjoint(Set<UUID> left, Set<UUID> right, String path) {
        for (UUID value : left) {
            if (right.contains(value)) {
                throw new IllegalArgumentException(path + " object sets must be disjoint");
            }
        }
    }
}
