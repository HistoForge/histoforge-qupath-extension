package qupath.ext.histoforge.pipeline;

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
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.spatial.TargetHierarchyPopulation;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.PolygonROI;

/**
 * Disposable target-local hierarchy state used while one recipe transaction is incomplete.
 *
 * <p>The view contains deep-detached current objects plus detached candidate objects. Redo
 * removals, model additions, and script class changes are applied only here. The working QuPath
 * hierarchy remains untouched until {@link ModelOutputCommitter} accepts the final transaction.
 */
final class StagedRegionView {

    private final Map<UUID, PathObject> objects;
    private final Map<UUID, String> initialExistingClasses;
    private final Set<UUID> candidateIds = new LinkedHashSet<>();

    private StagedRegionView(
            Map<UUID, PathObject> objects, Map<UUID, String> initialExistingClasses) {
        this.objects = new LinkedHashMap<>(objects);
        this.initialExistingClasses = Map.copyOf(initialExistingClasses);
    }

    /** Capture the complete polygonal population logically or spatially owned by one target. */
    static StagedRegionView capture(
            ExecutionScopeCodec.TargetRegion target, PathObjectHierarchy hierarchy) {
        return capture(target, hierarchy, List.of(target));
    }

    static StagedRegionView capture(
            ExecutionScopeCodec.TargetRegion target,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Map<UUID, PathObject> objects = new LinkedHashMap<>();
        Map<UUID, String> initialClasses = new LinkedHashMap<>();
        for (PathObject source :
                TargetHierarchyPopulation.select(target, hierarchy, selectionRegions)) {
            PathObject detached = ScriptExecutor.detachedClone(source);
            UUID objectId = Objects.requireNonNull(detached.getID(), "persistent object UUID");
            if (objects.putIfAbsent(objectId, detached) != null) {
                throw new IllegalArgumentException(
                        "staged region contains duplicate object UUID " + objectId);
            }
            if (detached.getPathClass() != null) {
                initialClasses.put(objectId, pathClass(detached));
            }
        }
        return new StagedRegionView(objects, initialClasses);
    }

    /** Remove current attributed output selected by one complete Redo replacement closure. */
    void removeOutputs(Set<ExecutionScopeCodec.OutputKey> replacementClosure) {
        Objects.requireNonNull(replacementClosure, "replacementClosure");
        if (replacementClosure.isEmpty()) {
            return;
        }
        objects.entrySet().removeIf(entry -> ownsAny(entry.getValue(), replacementClosure));
    }

    /** Add every candidate from a completed generation to the detached staged population. */
    void addGeneration(CandidateGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        if (generation.candidates().isEmpty()) {
            return;
        }
        Map<String, String> generationMetadata = new LinkedHashMap<>();
        ProvenanceCodec.writeMetadata(
                generationMetadata,
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        generation.output(),
                        generation.outputGenerationId()));
        for (CandidateGeneration.SlideCandidate candidate : generation.candidates()) {
            UUID candidateId = UUID.fromString(candidate.candidateId());
            PathObject object =
                    PathObjects.createDetectionObject(
                            candidate.roiSlide(), PathClass.fromString(candidate.pathClass()));
            object.setID(candidateId);
            candidate
                    .score()
                    .ifPresent(
                            score ->
                                    object.getMeasurementList()
                                            .put(
                                                    InferenceFeatureCodec
                                                            .DETECTION_SCORE_MEASUREMENT,
                                                    score));
            // Share immutable provenance text, not the object's editable metadata map.
            object.getMetadata().putAll(generationMetadata);
            if (objects.putIfAbsent(candidateId, object) != null
                    || !candidateIds.add(candidateId)) {
                throw new IllegalArgumentException(
                        "candidate UUID collides in staged region: " + candidateId);
            }
        }
    }

    /** Apply a validated script result to the detached view, preserving its old-class guard. */
    void applyClassChanges(Collection<HierarchyDelta.ClassChange> changes) {
        Objects.requireNonNull(changes, "changes");
        Set<UUID> changed = new LinkedHashSet<>();
        for (HierarchyDelta.ClassChange change : changes) {
            Objects.requireNonNull(change, "class change");
            if (!changed.add(change.objectId())) {
                throw new IllegalArgumentException(
                        "script result repeats object UUID " + change.objectId());
            }
            PathObject object = objects.get(change.objectId());
            if (object == null) {
                throw new IllegalArgumentException(
                        "script result references an object outside the staged region");
            }
            if (!pathClass(object).equals(change.expectedOldClass())) {
                throw new IllegalArgumentException(
                        "script class precondition does not match the staged object");
            }
            object.setPathClass(PathClass.fromString(change.newClass()));
        }
    }

    /** Complete staged objects in persistent-UUID order for one ordinary-script barrier. */
    List<PathObject> objects() {
        return objects.values().stream().sorted(Comparator.comparing(PathObject::getID)).toList();
    }

    /**
     * Model candidates arrive with accepted outlines, which this view never replaces or mutates.
     * Only those owned native polygons may skip source reconstruction during script selection;
     * scripts receive independent copies. Existing hierarchy objects retain full source checks.
     */
    boolean isValidatedPolygonCandidate(PathObject object) {
        return candidateIds.contains(object.getID())
                && objects.get(object.getID()) == object
                && object.getROI().getClass() == PolygonROI.class;
    }

    /** Find one exact staged object by persistent UUID. */
    Optional<PathObject> object(String objectId) {
        return Optional.ofNullable(
                objects.get(
                        UUID.fromString(
                                CanonicalCodec.requireCanonicalUuid(objectId, "object_id"))));
    }

    /** Resolve eligible source parents for one region-gated recipe step. */
    List<PathObject> eligibleParents(ModuleRef moduleRef, ModuleRecipe.Step step) {
        ModuleRef module = Objects.requireNonNull(moduleRef, "module_ref");
        Objects.requireNonNull(step, "step");
        if (!step.isRegionGated() || step.parentClass() == null || step.parentClass().isEmpty()) {
            throw new IllegalArgumentException(
                    "eligible parent lookup requires a gated model step");
        }
        Set<String> parentClasses = new LinkedHashSet<>();
        for (String value : step.parentClass()) {
            parentClasses.add(CanonicalCodec.requirePathClassName(value, "parent_class"));
        }
        List<PathObject> result = new ArrayList<>();
        for (PathObject object : objects.values()) {
            if (RoiRegistry.isAdministrativeRoi(object)
                    || object.getPathClass() == null
                    || !parentClasses.contains(pathClass(object))) {
                continue;
            }
            Optional<ProvenanceCodec.ObjectProvenance> provenance = provenance(object);
            if (provenance.isPresent()) {
                var producer = provenance.orElseThrow().output().producer();
                if (!producer.moduleRef().equals(module)
                        || !producer.stepId().equals(step.parentModel())) {
                    continue;
                }
            }
            if (object.getROI() == null || !object.getROI().isArea()) {
                throw new IllegalArgumentException(
                        "Point results cannot be used as area crop inputs.");
            }
            result.add(object);
        }
        return result.stream().sorted(Comparator.comparing(PathObject::getID)).toList();
    }

    /** Copy only objects eligible for the action's optional candidate-preservation filter. */
    List<CandidatePipeline.ExistingObjectSnapshot> candidatePreservationPopulation(
            boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        List<CandidatePipeline.ExistingObjectSnapshot> result = new ArrayList<>();
        for (PathObject object : objects()) {
            CandidatePipeline.ExistingObjectType type =
                    object.isAnnotation()
                            ? CandidatePipeline.ExistingObjectType.ANNOTATION
                            : object.isDetection()
                                    ? CandidatePipeline.ExistingObjectType.DETECTION
                                    : CandidatePipeline.ExistingObjectType.OTHER;
            Optional<String> objectClass =
                    Optional.ofNullable(object.getPathClass()).map(PathClass::toString);
            boolean attributed = provenance(object).isPresent();
            boolean administrative = RoiRegistry.isAdministrativeRoi(object);
            if (!CandidatePipeline.isSuppressionSource(
                    type, objectClass, attributed, administrative)) {
                continue;
            }
            result.add(
                    new CandidatePipeline.ExistingObjectSnapshot(
                            NativeRoiGeometry.convert(object.getROI()),
                            objectClass,
                            plane(object),
                            type,
                            attributed,
                            administrative));
        }
        return List.copyOf(result);
    }

    /** Rebuild candidate generations with the final classes visible after every script barrier. */
    List<CandidateGeneration> finalizedGenerations(Collection<CandidateGeneration> generations) {
        List<CandidateGeneration> result = new ArrayList<>();
        for (CandidateGeneration generation : generations) {
            List<CandidateGeneration.SlideCandidate> candidates = new ArrayList<>();
            for (CandidateGeneration.SlideCandidate candidate : generation.candidates()) {
                UUID candidateId = UUID.fromString(candidate.candidateId());
                PathObject staged = objects.get(candidateId);
                if (staged == null || !candidateIds.contains(candidateId)) {
                    throw new IllegalArgumentException(
                            "candidate is absent from the finalized staged region");
                }
                String finalClass = pathClass(staged);
                candidates.add(candidate.withPathClass(finalClass));
            }
            result.add(
                    new CandidateGeneration(
                            generation.reservation(),
                            generation.execution(),
                            generation.inputEvidence(),
                            generation.counts(),
                            candidates));
        }
        return List.copyOf(result);
    }

    /** Final class transitions for preserved current objects only. */
    List<HierarchyDelta.ClassChange> existingClassChanges() {
        List<HierarchyDelta.ClassChange> result = new ArrayList<>();
        for (Map.Entry<UUID, String> initial : initialExistingClasses.entrySet()) {
            PathObject current = objects.get(initial.getKey());
            if (current == null || current.getPathClass() == null) {
                continue;
            }
            String finalClass = pathClass(current);
            if (!initial.getValue().equals(finalClass)) {
                result.add(
                        new HierarchyDelta.ClassChange(
                                initial.getKey(), initial.getValue(), finalClass));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(HierarchyDelta.ClassChange::objectId))
                .toList();
    }

    private static boolean ownsAny(
            PathObject object, Set<ExecutionScopeCodec.OutputKey> replacementClosure) {
        return provenance(object)
                .map(ProvenanceCodec.ObjectProvenance::output)
                .filter(replacementClosure::contains)
                .isPresent();
    }

    private static Optional<ProvenanceCodec.ObjectProvenance> provenance(PathObject object) {
        return object.hasMetadata()
                ? ProvenanceCodec.readMetadata(object.getMetadata())
                : Optional.empty();
    }

    private static String pathClass(PathObject object) {
        if (object.getPathClass() == null) {
            throw new IllegalArgumentException(
                    "staged class operation requires a PathClass: " + object.getID());
        }
        return CanonicalCodec.requirePathClassName(
                object.getPathClass().toString(), "staged object PathClass");
    }

    private static SourceIdentityCodec.ImagePlaneIndex plane(PathObject object) {
        return new SourceIdentityCodec.ImagePlaneIndex(
                object.getROI().getZ(), object.getROI().getT());
    }
}
