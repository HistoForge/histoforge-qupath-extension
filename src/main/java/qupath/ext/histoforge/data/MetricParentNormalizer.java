package qupath.ext.histoforge.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.ext.histoforge.data.MetricPopulationCapture.ObjectFields;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Preserve valid captured parent links and repair the others before running Metrics. */
final class MetricParentNormalizer {

    private static final Comparator<IndexedNode> SMALLEST_CONTAINER_FIRST =
            Comparator.comparingDouble(IndexedNode::area)
                    .thenComparing(value -> value.node().objectId());

    private MetricParentNormalizer() {}

    /**
     * Keep valid biological parents and selected ROI/root parents. Repair an invalid link using the
     * smallest strictly larger object covering the child's JTS interior point, with UUID as the
     * equal-area tie-break. If none covers it, use the selected ROI or the slide's root.
     *
     * <p>ROI boundaries are administrative parents, not biological candidates. An absent parent
     * already denotes the root for slide Metrics, but must be repaired for ROI Metrics. Geometry
     * owners have validated every shape before this method; identity checks remain unconditional.
     *
     * <p>Return only changed links, in UUID order, as an immutable list. No assignment means the
     * captured parent is unchanged; an assignment with no parent moves the object to the slide
     * root.
     */
    static List<HierarchyDelta.ParentAssignment> normalize(
            List<ObjectFields> nodes,
            Optional<UUID> fallbackParentId,
            Set<UUID> validAdministrativeParentIds) {
        Objects.requireNonNull(nodes, "nodes");
        fallbackParentId = Objects.requireNonNull(fallbackParentId, "fallback_parent_id");
        validAdministrativeParentIds =
                Set.copyOf(
                        Objects.requireNonNull(
                                validAdministrativeParentIds, "valid_administrative_parent_ids"));

        // Capture already sorts once. Preserve arbitrary-order callers without copying the
        // normal capture path or retaining a second population-sized UUID lookup map.
        List<ObjectFields> ordered = nodes;
        boolean sorted = true;
        UUID previous = null;
        for (ObjectFields node : nodes) {
            UUID id = Objects.requireNonNull(node, "node").objectId();
            if (previous != null && previous.compareTo(id) > 0) {
                sorted = false;
            }
            previous = id;
        }
        if (!sorted) {
            ordered = new ArrayList<>(nodes);
            ordered.sort(Comparator.comparing(ObjectFields::objectId));
        }
        previous = null;
        for (ObjectFields node : ordered) {
            if (node.objectId().equals(previous)) {
                throw new IllegalArgumentException("region node UUIDs must be unique");
            }
            previous = node.objectId();
        }
        boolean administrativeOverlap =
                fallbackParentId.isPresent()
                        && findObject(ordered, fallbackParentId.orElseThrow()) != null;
        for (UUID id : validAdministrativeParentIds) {
            administrativeOverlap |= findObject(ordered, id) != null;
        }
        if (administrativeOverlap) {
            throw new IllegalArgumentException(
                    "administrative parents must be outside the biological population");
        }
        if (fallbackParentId.isPresent()
                && !validAdministrativeParentIds.contains(fallbackParentId.orElseThrow())) {
            throw new IllegalArgumentException(
                    "a non-root fallback parent must be a valid administrative anchor");
        }

        STRtree containerIndex = null;
        List<HierarchyDelta.ParentAssignment> changed = new ArrayList<>();
        for (ObjectFields child : ordered) {
            Optional<UUID> selected;
            if (child.currentParentId().equals(fallbackParentId)
                    || child.currentParentId()
                            .filter(validAdministrativeParentIds::contains)
                            .isPresent()) {
                selected = child.currentParentId();
            } else {
                Geometry childGeometry = child.geometry().copy();
                double childArea = childGeometry.getArea();
                Point interiorPoint = childGeometry.getInteriorPoint();
                if (validBiologicalParent(child, childArea, interiorPoint, ordered)) {
                    selected = child.currentParentId();
                } else {
                    if (containerIndex == null) {
                        containerIndex = buildContainerIndex(ordered);
                    }
                    selected = smallestContainer(child, childArea, interiorPoint, containerIndex);
                    if (selected.isEmpty()) {
                        selected = fallbackParentId;
                    }
                }
            }
            if (!selected.equals(child.currentParentId())) {
                changed.add(new HierarchyDelta.ParentAssignment(child.objectId(), selected));
            }
        }
        return List.copyOf(changed);
    }

    private static boolean validBiologicalParent(
            ObjectFields child, double childArea, Point interiorPoint, List<ObjectFields> nodes) {
        ObjectFields parent = child.currentParentId().map(id -> findObject(nodes, id)).orElse(null);
        if (parent == null) {
            return false;
        }
        Geometry parentGeometry = parent.geometry().copy();
        return parentGeometry.getArea() > childArea && parentGeometry.covers(interiorPoint);
    }

    private static ObjectFields findObject(List<ObjectFields> objects, UUID id) {
        int low = 0;
        int high = objects.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            ObjectFields object = objects.get(middle);
            int comparison = object.objectId().compareTo(id);
            if (comparison == 0) return object;
            if (comparison < 0) low = middle + 1;
            else high = middle - 1;
        }
        return null;
    }

    private static STRtree buildContainerIndex(List<ObjectFields> nodes) {
        STRtree index = new STRtree();
        for (ObjectFields node : nodes) {
            Geometry geometry = node.geometry().copy();
            index.insert(geometry.getEnvelopeInternal(), new IndexedNode(node, geometry.getArea()));
        }
        index.build();
        return index;
    }

    private static Optional<UUID> smallestContainer(
            ObjectFields child, double childArea, Point interiorPoint, STRtree index) {
        @SuppressWarnings("unchecked")
        List<IndexedNode> candidates = index.query(interiorPoint.getEnvelopeInternal());
        candidates.removeIf(
                candidate ->
                        candidate.node().objectId().equals(child.objectId())
                                || !(candidate.area() > childArea));
        candidates.sort(SMALLEST_CONTAINER_FIRST);
        // Query results are private lists. Test the best possible parent first, so nested
        // populations need not reconstruct every larger candidate's geometry.
        for (IndexedNode candidate : candidates) {
            if (candidate.node().geometry().copy().covers(interiorPoint)) {
                return Optional.of(candidate.node().objectId());
            }
        }
        return Optional.empty();
    }

    // The index owns bounds and area, not another complete population of JTS polygons.
    private record IndexedNode(ObjectFields node, double area) {}
}
