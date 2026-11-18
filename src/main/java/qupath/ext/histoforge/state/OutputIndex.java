package qupath.ext.histoforge.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Mutable per-working-image lookup derived only from current canonical object provenance. */
public final class OutputIndex {

    private final Map<ExecutionScopeCodec.OutputKey, LinkedHashMap<UUID, PathObject>> byOutput =
            new LinkedHashMap<>();
    private final Map<UUID, IndexedObject> byObjectId = new LinkedHashMap<>();

    private OutputIndex() {}

    /** Derived current ROI-versus-slide materialization state for one producer. */
    public enum ProducerMaterializationMode {
        NONE,
        ROI,
        SLIDE,
        CONFLICTING
    }

    /** Derived producer mode and exact current output keys; never persisted. */
    public record ProducerMaterialization(
            InferenceIdentityCodec.ModelStepIdentity producer,
            ProducerMaterializationMode mode,
            Set<ExecutionScopeCodec.OutputKey> currentOutputs) {
        public ProducerMaterialization {
            producer = Objects.requireNonNull(producer, "producer");
            mode = Objects.requireNonNull(mode, "mode");
            currentOutputs =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    Objects.requireNonNull(currentOutputs, "currentOutputs")));
        }
    }

    /** Build one derived index from every current non-root hierarchy object. */
    public static OutputIndex fromHierarchy(PathObjectHierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        OutputIndex index = new OutputIndex();
        index.addAll(hierarchy.getAllObjects(false));
        return index;
    }

    /** Presence is exactly one or more current attributed objects for this output key. */
    public boolean contains(ExecutionScopeCodec.OutputKey output) {
        LinkedHashMap<UUID, PathObject> objects = byOutput.get(Objects.requireNonNull(output));
        return objects != null && !objects.isEmpty();
    }

    /** Return the exact current attributed objects for one output, sorted by persistent UUID. */
    public List<PathObject> objects(ExecutionScopeCodec.OutputKey output) {
        LinkedHashMap<UUID, PathObject> objects = byOutput.get(Objects.requireNonNull(output));
        return objects == null ? List.of() : sortedObjects(objects.values());
    }

    /** Return every attributed object logically scoped to one ROI, independent of hierarchy. */
    public List<PathObject> objectsScopedToRoi(String roiId) {
        String canonical = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        List<PathObject> result = new ArrayList<>();
        for (Map.Entry<ExecutionScopeCodec.OutputKey, LinkedHashMap<UUID, PathObject>> entry :
                byOutput.entrySet()) {
            if (ExecutionScopeCodec.regionOf(entry.getKey().scope())
                            instanceof ExecutionScopeCodec.Roi roi
                    && canonical.equals(roi.roiId())) {
                result.addAll(entry.getValue().values());
            }
        }
        return sortedObjects(result);
    }

    /** Derive the current ROI, slide, absent, or conflicting mode for one producer. */
    public ProducerMaterialization materialization(
            InferenceIdentityCodec.ModelStepIdentity producer) {
        Objects.requireNonNull(producer, "producer");
        LinkedHashSet<ExecutionScopeCodec.OutputKey> outputs = new LinkedHashSet<>();
        boolean roi = false;
        boolean slide = false;
        for (ExecutionScopeCodec.OutputKey output : byOutput.keySet()) {
            if (!producer.equals(output.producer())) {
                continue;
            }
            outputs.add(output);
            if (ExecutionScopeCodec.regionOf(output.scope()) instanceof ExecutionScopeCodec.Roi) {
                roi = true;
            } else {
                slide = true;
            }
        }
        ProducerMaterializationMode mode =
                roi && slide
                        ? ProducerMaterializationMode.CONFLICTING
                        : roi
                                ? ProducerMaterializationMode.ROI
                                : slide
                                        ? ProducerMaterializationMode.SLIDE
                                        : ProducerMaterializationMode.NONE;
        return new ProducerMaterialization(producer, mode, outputs);
    }

    /**
     * Return complete current provenance in persistent UUID order for generation-store sweeping.
     */
    public List<ProvenanceCodec.ObjectProvenance> currentProvenance() {
        return byObjectId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().provenance())
                .toList();
    }

    /**
     * Add one newly current object. Unattributed objects are intentionally absent from the index.
     *
     * <p>Call only on the image-owner thread after the hierarchy accepted the same object.
     */
    public void add(PathObject object) {
        Objects.requireNonNull(object, "object");
        Optional<ProvenanceCodec.ObjectProvenance> provenance =
                object.hasMetadata()
                        ? ProvenanceCodec.readMetadata(object.getMetadata())
                        : Optional.empty();
        add(object, provenance);
    }

    /**
     * Index an already accepted batch on the image-owner thread. Equal metadata shares decoding
     * only for this call; current metadata and each attributed object's persistent UUID are still
     * checked.
     */
    public void addAll(Collection<PathObject> objects) {
        var reader = new ProvenanceCodec.MetadataReader();
        for (PathObject object : objects) {
            Objects.requireNonNull(object, "object");
            add(
                    object,
                    object.hasMetadata() ? reader.read(object.getMetadata()) : Optional.empty());
        }
    }

    private void add(PathObject object, Optional<ProvenanceCodec.ObjectProvenance> provenance) {
        if (provenance.isEmpty()) {
            return;
        }
        UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
        if (byObjectId.containsKey(objectId)) {
            throw new IllegalArgumentException(
                    "output index contains duplicate object UUID " + objectId);
        }
        ProvenanceCodec.ObjectProvenance value = provenance.orElseThrow();
        byObjectId.put(objectId, new IndexedObject(object, value));
        byOutput.computeIfAbsent(value.output(), ignored -> new LinkedHashMap<>())
                .put(objectId, object);
    }

    /** Remove one current attributed object by persistent UUID; absence is a no-op. */
    public void remove(UUID objectId) {
        IndexedObject removed = byObjectId.remove(Objects.requireNonNull(objectId, "objectId"));
        if (removed == null) {
            return;
        }
        LinkedHashMap<UUID, PathObject> objects = byOutput.get(removed.provenance().output());
        objects.remove(objectId);
        if (objects.isEmpty()) {
            byOutput.remove(removed.provenance().output());
        }
    }

    private static List<PathObject> sortedObjects(Collection<PathObject> objects) {
        return objects.stream().sorted(Comparator.comparing(PathObject::getID)).toList();
    }

    private record IndexedObject(PathObject object, ProvenanceCodec.ObjectProvenance provenance) {
        private IndexedObject {
            object = Objects.requireNonNull(object, "object");
            provenance = Objects.requireNonNull(provenance, "provenance");
        }
    }
}
