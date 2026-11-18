package qupath.ext.histoforge.spatial;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Select area and point objects owned by one logical target. */
public final class TargetHierarchyPopulation {

    private TargetHierarchyPopulation() {}

    /**
     * Select current objects by logical provenance first and spatial membership second.
     *
     * <p>The returned objects remain owned by the supplied hierarchy and must not escape the
     * caller's image-access boundary. Attributed objects follow their exact {@code RegionKey} even
     * when their geometry lies outside the target polygon. Unattributed objects use the same
     * interior-point ROI/slide membership as recipe staging.
     */
    public static List<PathObject> select(
            ExecutionScopeCodec.TargetRegion target, PathObjectHierarchy hierarchy) {
        return select(target, hierarchy, List.of(target));
    }

    public static List<PathObject> select(
            ExecutionScopeCodec.TargetRegion target,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hierarchy, "hierarchy");
        RoiRegistry rois = RoiRegistry.forTargets(hierarchy, selectionRegions);
        return hierarchy.getAllObjects(false).stream()
                .sorted(Comparator.comparing(PathObject::getID))
                .filter(object -> !rois.isBoundary(object))
                .filter(TargetHierarchyPopulation::isSupportedObject)
                .filter(object -> belongsToTarget(object, target, rois))
                .toList();
    }

    private static boolean belongsToTarget(
            PathObject object, ExecutionScopeCodec.TargetRegion target, RoiRegistry rois) {
        if (!plane(object).equals(target.plane())) {
            return false;
        }
        Optional<ProvenanceCodec.ObjectProvenance> provenance = provenance(object);
        if (provenance.isPresent()) {
            return ExecutionScopeCodec.regionOf(provenance.orElseThrow().output().scope())
                    .equals(target.key());
        }
        if (target.key() instanceof ExecutionScopeCodec.Roi roi) {
            return rois.membership(object) instanceof RoiRegistry.Member member
                    && member.roiId().equals(roi.roiId());
        }
        return target.geometrySlide()
                .covers(NativeRoiGeometry.convert(object.getROI()).getInteriorPoint());
    }

    private static Optional<ProvenanceCodec.ObjectProvenance> provenance(PathObject object) {
        return object.hasMetadata()
                ? ProvenanceCodec.readMetadata(object.getMetadata())
                : Optional.empty();
    }

    private static boolean isSupportedObject(PathObject object) {
        if ((!object.isDetection() && !object.isAnnotation())
                || object.getROI() == null
                || !(object.getROI().isArea() || object.getROI().isPoint())) {
            return false;
        }
        // A native counting annotation is a container, not one detected center. Splitting its
        // marks by target requires a separate capture contract; leave those containers alone.
        if (object.getROI().isPoint() && object.getROI().getNumPoints() != 1) {
            return false;
        }
        Geometry geometry = NativeRoiGeometry.convert(object.getROI());
        if (geometry instanceof Point || geometry instanceof MultiPoint) {
            CanonicalGeometryCodec.validatePointGeometry(geometry);
            return true;
        }
        if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
            return false;
        }
        CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        if (!(geometry.getArea() > 0.0)) {
            throw new IllegalArgumentException(
                    "target hierarchy object must have positive-area geometry");
        }
        return true;
    }

    private static SourceIdentityCodec.ImagePlaneIndex plane(PathObject object) {
        return new SourceIdentityCodec.ImagePlaneIndex(
                object.getROI().getZ(), object.getROI().getT());
    }
}
