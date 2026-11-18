package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * Shared, compiled morphometry primitives for module metric scripts, injected into the Groovy
 * binding as {@code m} by {@link ScriptBindings}. Scripts call e.g. {@code m.eccentricity(obj)} —
 * no imports, and the exposed surface is a versioned contract.
 *
 * <p>Area and containment use the QuPath outline. Moment and convex-hull calculations use JTS.
 */
public final class MetricUtils {

    private static final Logger logger = LoggerFactory.getLogger(MetricUtils.class);
    public static final MetricUtils INSTANCE = new MetricUtils();

    private MetricUtils() {}

    /** QuPath outline area in square image pixels. */
    public double area(PathObject o) {
        return areaOutline(o).getArea();
    }

    private static ROI areaOutline(PathObject object) {
        ROI outline = object.getROI();
        if (!outline.isArea()) {
            throw new IllegalArgumentException(
                    "Area measurements require an area outline, not points.");
        }
        return outline;
    }

    /** Objects whose PathClass name equals {@code name}. */
    public List<PathObject> byClass(List<PathObject> children, String name) {
        List<PathObject> out = new ArrayList<>();
        for (PathObject o : children) {
            if (o.getPathClass() != null && name.equals(o.getPathClass().toString())) {
                out.add(o);
            }
        }
        return out;
    }

    /** {@code value/denom*100}, or {@code null} if {@code denom} is null or ≤ 0. */
    public Double pct(double value, Double denom) {
        return (denom != null && denom > 0) ? value / denom * 100.0 : null;
    }

    /** regionprops-equivalent eccentricity from the polygon's normalized central moments. */
    public double eccentricity(PathObject o) {
        double[] e = momentEigs(o);
        return (e[0] <= 0) ? 0.0 : Math.sqrt(Math.max(0.0, 1.0 - e[1] / e[0]));
    }

    /** regionprops-equivalent minor-axis length in px: {@code 4*sqrt(min eigenvalue)}. */
    public double minorAxis(PathObject o) {
        return 4.0 * Math.sqrt(Math.max(0.0, momentEigs(o)[1]));
    }

    /** regionprops-equivalent major-axis length in px: {@code 4*sqrt(max eigenvalue)}. */
    public double majorAxis(PathObject o) {
        return 4.0 * Math.sqrt(Math.max(0.0, momentEigs(o)[0]));
    }

    /**
     * regionprops-equivalent solidity: the outline's area over its convex hull's area.
     *
     * <p>Measures concavity — a U-bend, a crescent, or two objects fused at a waist all read well
     * below 1.0. The axis measures cannot see this: a dented shape still has a near-round inertia
     * ellipse, so it passes a {@code minorAxis/majorAxis} gate untouched.
     *
     * <p>Measured on the exterior ring only, matching {@link #momentEigs}. Holes therefore don't
     * count against it — a lumen inside a tubule argues <em>for</em> a round cross-section, not
     * against.
     *
     * <p>Being a ratio of two areas, it barely moves on the ~2 px vertex spacing of a
     * mask-vectorized outline (~0.006 measured over 39 real tubules) — unlike circularity, whose
     * perimeter term the same staircase inflates by ~26%.
     */
    public double solidity(PathObject o) {
        Polygon main = (Polygon) mainPoly(NativeRoiGeometry.convert(o.getROI()));
        Polygon outline = main.getFactory().createPolygon(main.getExteriorRing().getCoordinates());
        double hull = outline.convexHull().getArea();
        return hull > 0 ? outline.getArea() / hull : 0.0;
    }

    /** Largest polygon of a (possibly multi-part) geometry. */
    public Geometry mainPoly(Geometry g) {
        if (!(g instanceof Polygon || g instanceof MultiPolygon)) {
            throw new IllegalArgumentException(
                    "Shape measurements require a polygonal outline, not points.");
        }
        if (g.getNumGeometries() <= 1) {
            return g;
        }
        Geometry best = g.getGeometryN(0);
        for (int i = 1; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i).getArea() > best.getArea()) {
                best = g.getGeometryN(i);
            }
        }
        return best;
    }

    /**
     * Match a child's centroid to the first containing parent in caller order, using the native
     * outline's containment rule. Keep the largest child per parent; equal areas keep the first.
     * Return {@code [child, parent]} pairs in order of each parent's first match.
     */
    public List<PathObject[]> matchLargestByCentroid(
            List<PathObject> children, List<PathObject> parents) {
        long started = System.nanoTime();
        logger.debug(
                "Metrics centroid-match parent index construction started: {} parents",
                parents.size());
        boolean reportProgress = logger.isDebugEnabled();
        STRtree parentIndex = new STRtree();
        for (int order = 0; order < parents.size(); order++) {
            if (reportProgress && order > 0 && order % 100_000 == 0) {
                logger.debug(
                        "Metrics centroid-match parent index construction progress: {} of {} parents indexed; {} ms elapsed",
                        order,
                        parents.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PathObject parent = parents.get(order);
            ROI outline = areaOutline(parent);
            parentIndex.insert(
                    new Envelope(
                            outline.getBoundsX(), outline.getBoundsX() + outline.getBoundsWidth(),
                            outline.getBoundsY(), outline.getBoundsY() + outline.getBoundsHeight()),
                    new IndexedParent(order, parent, outline));
        }
        parentIndex.build();
        logger.debug(
                "Metrics centroid-match parent index construction finished in {} ms; {} parents",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                parents.size());

        started = System.nanoTime();
        logger.debug("Metrics centroid-match child matching started: {} children", children.size());
        Map<PathObject, PathObject> best = new LinkedHashMap<>();
        int examined = 0;
        for (PathObject c : children) {
            if (reportProgress && examined > 0 && examined % 100_000 == 0) {
                logger.debug(
                        "Metrics centroid-match child matching progress: {} of {} children examined, {} matched parents; {} ms elapsed",
                        examined,
                        children.size(),
                        best.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            examined++;
            ROI childOutline = areaOutline(c);
            IndexedParent matched =
                    firstContainingParent(
                            parentIndex, childOutline.getCentroidX(), childOutline.getCentroidY());
            if (matched != null) {
                PathObject parent = matched.object();
                PathObject current = best.get(parent);
                if (current == null || area(c) > area(current)) {
                    best.put(parent, c);
                }
            }
        }
        List<PathObject[]> pairs = new ArrayList<>();
        for (Map.Entry<PathObject, PathObject> e : best.entrySet()) {
            pairs.add(new PathObject[] {e.getValue(), e.getKey()}); // [child, parent]
        }
        logger.debug(
                "Metrics centroid-match child matching finished in {} ms; {} children, {} matched pairs",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                children.size(),
                pairs.size());
        return pairs;
    }

    private static IndexedParent firstContainingParent(STRtree index, double x, double y) {
        @SuppressWarnings("unchecked")
        List<IndexedParent> candidates = index.query(new Envelope(x, x, y, y));
        IndexedParent first = null;
        for (IndexedParent candidate : candidates) {
            if ((first == null || candidate.order() < first.order())
                    && candidate.outline().contains(x, y)) {
                first = candidate;
            }
        }
        return first;
    }

    // Capture the outline before examining children, so replacing a parent's ROI cannot change
    // the indexed shape halfway through matching.
    private record IndexedParent(int order, PathObject object, ROI outline) {}

    /** Normalized central second-moment eigenvalues {@code [l1 >= l2]} of a polygon. */
    private double[] momentEigs(PathObject o) {
        Coordinate[] coords =
                ((Polygon) mainPoly(NativeRoiGeometry.convert(o.getROI())))
                        .getExteriorRing()
                        .getCoordinates();
        int n = coords.length - 1;
        double a = 0, cx = 0, cy = 0, mxx = 0, myy = 0, mxy = 0;
        for (int i = 0; i < n; i++) {
            double x0 = coords[i].x, y0 = coords[i].y, x1 = coords[i + 1].x, y1 = coords[i + 1].y;
            double cr = x0 * y1 - x1 * y0;
            a += cr;
            cx += (x0 + x1) * cr;
            cy += (y0 + y1) * cr;
            mxx += (x0 * x0 + x0 * x1 + x1 * x1) * cr;
            myy += (y0 * y0 + y0 * y1 + y1 * y1) * cr;
            mxy += (x0 * y1 + 2 * x0 * y0 + 2 * x1 * y1 + x1 * y0) * cr;
        }
        a *= 0.5;
        if (Math.abs(a) < 1e-9) {
            return new double[] {0, 0};
        }
        cx /= (6 * a);
        cy /= (6 * a);
        mxx /= 12.0;
        myy /= 12.0;
        mxy /= 24.0;
        double mu20 = mxx / a - cx * cx, mu02 = myy / a - cy * cy, mu11 = mxy / a - cx * cy;
        double t = (mu20 + mu02) / 2.0, half = (mu20 - mu02) / 2.0;
        double d = Math.sqrt(Math.max(0.0, half * half + mu11 * mu11));
        return new double[] {t + d, t - d};
    }
}
