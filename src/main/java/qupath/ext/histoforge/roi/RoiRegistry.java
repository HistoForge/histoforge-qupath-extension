package qupath.ext.histoforge.roi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

/** Native annotation ownership and the captured boundaries used for spatial membership. */
public final class RoiRegistry {

    private final Map<String, PathObject> annotationsById;
    private final Map<String, RoiEntry> roisById;
    private final Map<SourceIdentityCodec.ImagePlaneIndex, STRtree> spatialIndexes;

    private RoiRegistry(Map<String, PathObject> annotationsById, Map<String, RoiEntry> roisById) {
        this.annotationsById = Map.copyOf(annotationsById);
        this.roisById = Map.copyOf(roisById);
        this.spatialIndexes = buildSpatialIndexes(this.roisById.values());
    }

    /** Present PathObject provenance could not be decoded as the persisted contract. */
    public static final class MalformedObjectProvenanceException extends IllegalArgumentException {
        public MalformedObjectProvenanceException(UUID objectId, IllegalArgumentException cause) {
            super("object " + objectId + " has malformed HistoForge provenance", cause);
        }
    }

    /** Authoritative hierarchy structure cannot be interpreted without guessing. */
    public static final class CorruptHierarchyException extends IllegalArgumentException {
        public CorruptHierarchyException(String message, RuntimeException cause) {
            super(message, cause);
        }
    }

    /** Closed result of assigning one object to the logical ROI metric domain. */
    public sealed interface RoiMembership permits Member, Outside, Ambiguous, MissingLogicalRoi {}

    /** Ownership decision and the same validated provenance used to make it, for export capture. */
    public record MembershipEvidence(
            RoiMembership membership, Optional<ProvenanceCodec.ObjectProvenance> provenance) {}

    /** The object has one annotation owner, from provenance or the selected boundaries. */
    public record Member(String roiId) implements RoiMembership {
        public Member {
            roiId = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        }
    }

    /** The object has no annotation owner and lies outside the selected boundaries. */
    public record Outside() implements RoiMembership {}

    /** Spatial fallback matched multiple current ROIs and cannot choose safely. */
    public record Ambiguous(Set<String> roiIds) implements RoiMembership {
        public Ambiguous {
            if (roiIds == null || roiIds.size() < 2) {
                throw new IllegalArgumentException("ambiguous ROI membership requires two ROIs");
            }
            Set<String> normalized = new TreeSet<>();
            for (String roiId : roiIds) {
                normalized.add(CanonicalCodec.requireCanonicalUuid(roiId, "roi_id"));
            }
            roiIds = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
        }
    }

    /** Provenance names an ROI that is absent from the captured hierarchy. */
    public record MissingLogicalRoi(String roiId) implements RoiMembership {
        public MissingLogicalRoi {
            roiId = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        }
    }

    /**
     * Capture current ROI geometry and validate that every hierarchy object has a unique UUID.
     *
     * <p>The result is an immutable point-in-time view. Callers rebuild it after hierarchy
     * mutation; it is not a completion or currency cache.
     */
    public static RoiRegistry fromHierarchy(PathObjectHierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        return fromHierarchyObjects(hierarchy.getFlattenedObjectList(null));
    }

    /**
     * Validate the complete root-first list returned by QuPath's flattened-hierarchy API.
     *
     * <p>The caller owns the hierarchy thread and may reuse this list during the same capture; the
     * registry retains only annotation references, not the full population.
     */
    public static RoiRegistry fromHierarchyObjects(List<PathObject> objects) {
        return fromHierarchyObjects(objects, Set.of());
    }

    private static RoiRegistry fromHierarchyObjects(
            List<PathObject> objects, Set<UUID> selectedIds) {
        try {
            Objects.requireNonNull(objects, "hierarchy objects");
            if (objects.isEmpty() || !objects.getFirst().isRootObject()) {
                throw new IllegalArgumentException("complete hierarchy must start with its root");
            }
            return captureHierarchy(objects, selectedIds);
        } catch (CorruptHierarchyException failure) {
            throw failure;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptHierarchyException(
                    "authoritative hierarchy contains invalid HistoForge ROI state", failure);
        }
    }

    private static RoiRegistry captureHierarchy(List<PathObject> objects, Set<UUID> selectedIds) {
        Set<UUID> objectIds = new HashSet<>();
        Map<String, PathObject> annotations = new LinkedHashMap<>();
        Map<String, RoiEntry> rois = new LinkedHashMap<>();
        for (PathObject object : objects) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            if (!objectIds.add(objectId)) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate object UUID " + objectId);
            }
            boolean administrative = isAdministrativeRoi(object);
            // Generated annotations cannot own ROI output. Keep explicit selections for rois(),
            // and all other possible owners, including unselected and non-area annotations.
            // This filter reads only identity and metadata; admission still checks geometry later.
            if (object.isAnnotation()
                    && (administrative
                            || selectedIds.contains(objectId)
                            || !object.hasMetadata()
                            || !object.getMetadata()
                                    .containsKey(HistoforgePersistenceKeys.OBJECT_PROVENANCE))) {
                annotations.put(objectId.toString(), object);
            }
            if (!administrative) {
                continue;
            }
            RoiEntry entry = roiEntry(object);
            if (rois.put(entry.roiId(), entry) != null) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate HistoForge ROI UUID " + entry.roiId());
            }
        }
        return new RoiRegistry(annotations, rois);
    }

    /** Use only this action's captured ROI outlines for unattributed-object membership. */
    public static RoiRegistry forTargets(
            PathObjectHierarchy hierarchy, List<ExecutionScopeCodec.TargetRegion> targets) {
        return forTargetsFromHierarchyObjects(hierarchy.getFlattenedObjectList(null), targets);
    }

    /** Use a complete owner-thread population already captured for the same operation. */
    public static RoiRegistry forTargetsFromHierarchyObjects(
            List<PathObject> objects, List<ExecutionScopeCodec.TargetRegion> targets) {
        Set<UUID> selectedIds = new HashSet<>();
        for (var target : targets) {
            if (target.key() instanceof ExecutionScopeCodec.Roi roi) {
                selectedIds.add(UUID.fromString(roi.roiId()));
            }
        }
        RoiRegistry current = fromHierarchyObjects(objects, selectedIds);
        if (targets.stream()
                .anyMatch(target -> target.key() instanceof ExecutionScopeCodec.Slide)) {
            return current;
        }
        Map<String, RoiEntry> selected = new LinkedHashMap<>();
        for (var target : targets) {
            String id = ((ExecutionScopeCodec.Roi) target.key()).roiId();
            if (selected.put(id, new RoiEntry(id, target.geometrySlide(), target.plane()))
                    != null) {
                throw new IllegalArgumentException("Repeated selected annotation: " + id);
            }
        }
        return new RoiRegistry(current.annotationsById, selected);
    }

    /** Ordinary area annotations can be boundaries without acquiring HistoForge metadata. */
    public static boolean isOrdinaryAreaAnnotation(PathObject object) {
        return object != null
                && object.isAnnotation()
                && !isAdministrativeRoi(object)
                && object.getROI() != null
                && object.getROI().isArea()
                && object.getROI().getArea() > 0
                && (!object.hasMetadata()
                        || !object.getMetadata()
                                .containsKey(HistoforgePersistenceKeys.OBJECT_PROVENANCE));
    }

    /** A boundary is not a biological input, even when its class matches a model output. */
    public boolean isBoundary(PathObject object) {
        return isAdministrativeRoi(object) || roisById.containsKey(object.getID().toString());
    }

    /** Identify the administrative ROI marker exactly; displayed class and name are irrelevant. */
    public static boolean isAdministrativeRoi(PathObject object) {
        return object != null
                && object.hasMetadata()
                && HistoforgeKeys.ROI_TYPE_VALUE.equals(
                        object.getMetadata().get(HistoforgeKeys.ROI_TYPE));
    }

    /** Resolve a saved owner UUID to its native area annotation, independently of selection. */
    public Optional<PathObject> findRoi(String roiId) {
        String canonical = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        return Optional.ofNullable(annotationsById.get(canonical))
                .filter(object -> isAdministrativeRoi(object) || isOrdinaryAreaAnnotation(object));
    }

    /** Return present annotations from the spatial selection, in canonical UUID order. */
    public List<PathObject> rois() {
        return roisById.keySet().stream()
                .sorted()
                .map(annotationsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Capture exact target regions and reject missing, duplicate, outside-image, or overlapping
     * selected ROIs.
     */
    public List<ExecutionScopeCodec.TargetRegion> captureTargets(
            Collection<String> roiIds, int imageWidth, int imageHeight) {
        if (roiIds == null || roiIds.isEmpty()) {
            throw new IllegalArgumentException("ROI target selection must be nonempty");
        }
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        List<RoiEntry> selected = new ArrayList<>();
        Set<String> unique = new TreeSet<>();
        for (String roiId : roiIds) {
            String canonical = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
            if (!unique.add(canonical)) {
                throw new IllegalArgumentException("ROI target selection contains duplicates");
            }
            RoiEntry entry =
                    roiEntry(
                            findRoi(canonical)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Selected area annotation is missing: "
                                                                    + canonical)));
            requireInsideImage(entry, imageWidth, imageHeight);
            selected.add(entry);
        }
        selected.sort((left, right) -> left.roiId().compareTo(right.roiId()));
        requireNoPositiveAreaOverlap(selected);

        return selected.stream()
                .map(
                        entry ->
                                new ExecutionScopeCodec.TargetRegion(
                                        new ExecutionScopeCodec.Roi(entry.roiId()),
                                        entry.geometry(),
                                        entry.plane(),
                                        CanonicalGeometryCodec.canonicalDigest(entry.geometry())))
                .toList();
    }

    /**
     * Resolve logical ROI provenance first; only unattributed or slide-attributed polygons use
     * interior-point spatial membership.
     */
    public RoiMembership membership(PathObject object) {
        return membershipEvidence(object).membership();
    }

    public MembershipEvidence membershipEvidence(PathObject object) {
        return resolveMembership(object, null, null);
    }

    /** Reuse exact successful provenance decodes within the caller's synchronous capture. */
    public MembershipEvidence membershipEvidence(
            PathObject object, ProvenanceCodec.MetadataReader provenanceReader) {
        return resolveMembership(
                object, null, Objects.requireNonNull(provenanceReader, "provenance reader"));
    }

    /**
     * Borrow this object's private, already-validated outline for one synchronous membership check.
     * The caller must have validated the same current object geometry; it is read only during this
     * call and never retained. Logical provenance still takes precedence.
     */
    public RoiMembership membershipUsingValidatedGeometry(PathObject object, Geometry geometry) {
        return membershipEvidenceUsingValidatedGeometry(object, geometry).membership();
    }

    public MembershipEvidence membershipEvidenceUsingValidatedGeometry(
            PathObject object, Geometry geometry) {
        return resolveMembership(
                object, Objects.requireNonNull(geometry, "validated geometry"), null);
    }

    /**
     * Borrow validated geometry and an operation-scoped provenance reader without retaining either.
     */
    public MembershipEvidence membershipEvidenceUsingValidatedGeometry(
            PathObject object, Geometry geometry, ProvenanceCodec.MetadataReader provenanceReader) {
        return resolveMembership(
                object,
                Objects.requireNonNull(geometry, "validated geometry"),
                Objects.requireNonNull(provenanceReader, "provenance reader"));
    }

    private MembershipEvidence resolveMembership(
            PathObject object,
            Geometry validatedGeometry,
            ProvenanceCodec.MetadataReader provenanceReader) {
        Objects.requireNonNull(object, "object");
        UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
        final Optional<ProvenanceCodec.ObjectProvenance> provenance;
        try {
            if (!object.hasMetadata()) {
                provenance = Optional.empty();
            } else {
                provenance =
                        provenanceReader == null
                                ? ProvenanceCodec.readMetadata(object.getMetadata())
                                : provenanceReader.read(object.getMetadata());
            }
        } catch (IllegalArgumentException failure) {
            throw new MalformedObjectProvenanceException(objectId, failure);
        }
        if (provenance.isPresent()) {
            ExecutionScopeCodec.RegionKey region =
                    ExecutionScopeCodec.regionOf(provenance.orElseThrow().output().scope());
            if (region instanceof ExecutionScopeCodec.Roi roi) {
                RoiMembership membership =
                        findRoi(roi.roiId()).isPresent()
                                ? new Member(roi.roiId())
                                : new MissingLogicalRoi(roi.roiId());
                return new MembershipEvidence(membership, provenance);
            }
        }
        try {
            return new MembershipEvidence(spatialMembership(object, validatedGeometry), provenance);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptHierarchyException(
                    "object " + objectId + " has invalid spatial hierarchy state", failure);
        }
    }

    private RoiMembership spatialMembership(PathObject object, Geometry validatedGeometry) {
        ROI roi = object.getROI();
        if (roi == null || !(roi.isArea() || roi.isPoint())) {
            return new Outside();
        }
        if (roi.isPoint() && roi.getNumPoints() != 1) {
            return new Outside();
        }
        Geometry geometry = validatedGeometry;
        if (geometry == null) {
            if (roi.isPoint()) {
                geometry = NativeRoiGeometry.convert(roi);
                CanonicalGeometryCodec.validatePointGeometry(geometry);
            } else {
                geometry = NativeRoiGeometry.convertValidatedPolygonal(roi);
            }
            if (roi.isArea() && !(geometry.getArea() > 0.0)) {
                throw new IllegalArgumentException("object geometry must have positive area");
            }
        }
        SourceIdentityCodec.ImagePlaneIndex plane = plane(roi);
        STRtree index = spatialIndexes.get(plane);
        if (index == null) {
            return new Outside();
        }
        Point interiorPoint = geometry.getInteriorPoint();

        @SuppressWarnings("unchecked")
        List<RoiEntry> possible = index.query(interiorPoint.getEnvelopeInternal());
        Set<String> matches = new TreeSet<>();
        for (RoiEntry entry : possible) {
            if (entry.geometry().covers(interiorPoint)) {
                matches.add(entry.roiId());
            }
        }
        if (matches.isEmpty()) {
            return new Outside();
        }
        if (matches.size() == 1) {
            return new Member(matches.iterator().next());
        }
        return new Ambiguous(matches);
    }

    private static RoiEntry roiEntry(PathObject object) {
        if (!object.isAnnotation() || object.getROI() == null || !object.getROI().isArea()) {
            throw new IllegalArgumentException("ROI boundary requires an area annotation");
        }
        Geometry geometry = NativeRoiGeometry.convert(object.getROI());
        if (isAdministrativeRoi(object) && !geometry.equalsTopo(geometry.getEnvelope())) {
            throw new IllegalArgumentException(
                    "HistoForge ROI must be an axis-aligned rectangle: " + object.getID());
        }
        return new RoiEntry(object.getID().toString(), geometry, plane(object.getROI()));
    }

    private static Geometry validatedAreaCopy(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        try {
            CanonicalGeometryCodec.validatePolygonalGeometry(copy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    path + " must be a valid Polygon/MultiPolygon", error);
        }
        if (!(copy.getArea() > 0.0)) {
            throw new IllegalArgumentException(path + " must have positive area");
        }
        return copy;
    }

    private static SourceIdentityCodec.ImagePlaneIndex plane(ROI roi) {
        return new SourceIdentityCodec.ImagePlaneIndex(
                roi.getImagePlane().getZ(), roi.getImagePlane().getT());
    }

    private static Map<SourceIdentityCodec.ImagePlaneIndex, STRtree> buildSpatialIndexes(
            Collection<RoiEntry> rois) {
        Map<SourceIdentityCodec.ImagePlaneIndex, STRtree> indexes = new LinkedHashMap<>();
        for (RoiEntry roi : rois) {
            indexes.computeIfAbsent(roi.plane(), ignored -> new STRtree())
                    .insert(roi.geometry().getEnvelopeInternal(), roi);
        }
        indexes.values().forEach(STRtree::build);
        return Map.copyOf(indexes);
    }

    private static void requireInsideImage(RoiEntry roi, int width, int height) {
        var bounds = roi.geometry().getEnvelopeInternal();
        if (bounds.getMinX() < 0.0
                || bounds.getMinY() < 0.0
                || bounds.getMaxX() > width
                || bounds.getMaxY() > height) {
            throw new IllegalArgumentException(
                    "Selected annotation lies outside image bounds: " + roi.roiId());
        }
    }

    private static void requireNoPositiveAreaOverlap(List<RoiEntry> rois) {
        for (int left = 0; left < rois.size(); left++) {
            RoiEntry first = rois.get(left);
            for (int right = left + 1; right < rois.size(); right++) {
                RoiEntry second = rois.get(right);
                if (!first.plane().equals(second.plane())) {
                    continue;
                }
                if (first.geometry().intersection(second.geometry()).getArea() > 0.0) {
                    throw new IllegalArgumentException(
                            "Selected annotation boundaries overlap: "
                                    + first.roiId()
                                    + ", "
                                    + second.roiId());
                }
            }
        }
    }

    private record RoiEntry(
            String roiId, Geometry geometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        private RoiEntry {
            roiId = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
            geometry = validatedAreaCopy(geometry, "ROI boundary");
            plane = Objects.requireNonNull(plane, "plane");
        }
    }
}
