package qupath.ext.histoforge.data;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/** Own a captured outline without retaining an editable object or its hierarchy. */
final class MetricObjectGeometry {

    // Keep either a known immutable host outline or private validated JTS geometry, never both.
    private final Geometry geometry;
    private final ROI outline;

    private MetricObjectGeometry(Geometry geometry, ROI outline) {
        this.geometry = geometry;
        this.outline = outline;
    }

    static MetricObjectGeometry copyOf(Geometry source) {
        return new MetricObjectGeometry(validatedCopy(source), null);
    }

    /**
     * The caller gives up mutation access to the validated geometry. Ordinary native outlines are
     * authoritative: do not reconstruct them merely to compare their points and measurements.
     */
    static MetricObjectGeometry takeValidatedGeometry(
            Geometry geometry, ImagePlane plane, ROI originalOutline) {
        if (originalOutline != null && isShareable(originalOutline)) {
            ROI retained =
                    originalOutline.getImagePlane().equals(plane)
                            ? originalOutline
                            : originalOutline.updatePlane(plane);
            return new MetricObjectGeometry(null, retained);
        }
        return new MetricObjectGeometry(geometry, null);
    }

    private static boolean isShareable(ROI roi) {
        // Subclasses may be mutable. GeometryROI also has a point-location cache that can retain
        // its creator's mutable geometry in supported QuPath versions; keep the private fallback.
        Class<?> type = roi.getClass();
        return type == PolygonROI.class || type == RectangleROI.class || type == EllipseROI.class;
    }

    /** Return defensive geometry; callers may mutate it without changing captured input. */
    Geometry copy() {
        return outline == null ? geometry.copy() : NativeRoiGeometry.convert(outline);
    }

    /** Fresh script objects share immutable outlines, not editable fields or parent links. */
    ROI scriptRoi(ImagePlane plane) {
        if (outline == null) {
            return GeometryTools.geometryToROI(geometry.copy(), plane);
        }
        return outline.getImagePlane().equals(plane) ? outline : outline.updatePlane(plane);
    }

    void writeInput(DataOutputStream out, ImagePlane plane) throws IOException {
        if (outline == null) {
            MetricInputEncoding.writeGeometry(out, geometry, plane);
        } else {
            MetricInputEncoding.writeOutline(out, scriptRoi(plane));
        }
    }

    // Value comparisons are outside the capture/hash hot path. Use the same outline encoding so
    // native type, plane and cached measurements cannot be lost through JTS equality.
    private String identity() {
        return MetricInputEncoding.outlineDigest(
                this, outline == null ? ImagePlane.getDefaultPlane() : outline.getImagePlane());
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof MetricObjectGeometry value
                        && identity().equals(value.identity());
    }

    @Override
    public int hashCode() {
        return identity().hashCode();
    }

    private static Geometry validatedCopy(Geometry source) {
        Geometry copy = Objects.requireNonNull(source, "geometry_slide").copy();
        if (!(copy instanceof Polygon || copy instanceof MultiPolygon)) {
            throw new IllegalArgumentException("geometry_slide must be Polygon or MultiPolygon");
        }
        CanonicalGeometryCodec.validatePolygonalGeometry(copy);
        if (!(copy.getArea() > 0.0)) {
            throw new IllegalArgumentException("geometry_slide must have positive area");
        }
        return copy;
    }
}
