package qupath.ext.histoforge.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/** Versioned Metrics identity: small canonical metadata followed by streamed binary outlines. */
final class MetricInputEncoding {

    private static final byte[] DOMAIN = CanonicalCodec.utf8Bytes("histoforge-metric-input-v2");
    private static final int BUFFER_BYTES = 8192;

    private MetricInputEncoding() {}

    static String digest(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            SourceIdentityCodec.PixelCalibration calibration,
            InferenceIdentityCodec.ModuleContentIdentity module,
            MetricScriptSnapshot script,
            List<MetricRegionSnapshot> regions) {
        return CanonicalCodec.sha256Incrementally(
                digest ->
                        write(
                                clientBuild,
                                calibration,
                                module,
                                script,
                                regions,
                                new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
    }

    /**
     * Write the v2 preimage without retaining population-sized bytes. Lengths/counts are
     * nonnegative int32, integers are big-endian, and strings are strict UTF-8. Coordinates must be
     * finite; native measurements retain their raw IEEE-754 bits, including NaN and signed zero.
     * The caller owns the output stream.
     */
    static void write(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            SourceIdentityCodec.PixelCalibration calibration,
            InferenceIdentityCodec.ModuleContentIdentity module,
            MetricScriptSnapshot script,
            List<MetricRegionSnapshot> regions,
            OutputStream output) {
        try {
            DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(output, BUFFER_BYTES));
            out.write(DOMAIN);
            writeBytes(
                    out,
                    CanonicalCodec.canonicalBytes(
                            inputMetadata(clientBuild, calibration, module, script)));
            out.writeInt(regions.size());
            for (MetricRegionSnapshot region : regions) {
                writeBytes(
                        out,
                        CanonicalCodec.canonicalBytes(ExecutionScopeCodec.toJson(region.target())));
                out.writeInt(region.objects().size());
                ImagePlane plane =
                        ImagePlane.getPlane(
                                region.target().plane().z(), region.target().plane().t());
                for (MetricObjectSnapshot object : region.objects()) {
                    writeUuid(out, object.objectId());
                    out.writeByte(
                            switch (object.objectType()) {
                                case ANNOTATION -> 1;
                                case DETECTION -> 2;
                            });
                    writeBytes(out, CanonicalCodec.utf8Bytes(object.pathClass()));
                    out.writeBoolean(object.parentObjectId().isPresent());
                    if (object.parentObjectId().isPresent()) {
                        writeUuid(out, object.parentObjectId().orElseThrow());
                    }
                    object.writeOutline(out, plane);
                }
            }
            out.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Value equality uses the same declared outline fields as the input fingerprint. */
    static String outlineDigest(MetricObjectGeometry outline, ImagePlane plane) {
        return CanonicalCodec.sha256Incrementally(
                digest -> {
                    try (var out =
                            new DataOutputStream(
                                    new BufferedOutputStream(
                                            new DigestOutputStream(
                                                    OutputStream.nullOutputStream(), digest),
                                            BUFFER_BYTES))) {
                        outline.writeInput(out, plane);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
    }

    static void writeOutline(DataOutputStream out, ROI outline) throws IOException {
        Class<?> type = outline.getClass();
        int tag;
        if (type == PolygonROI.class) {
            tag = 1;
        } else if (type == RectangleROI.class) {
            tag = 2;
        } else if (type == EllipseROI.class) {
            tag = 3;
        } else {
            throw new IllegalArgumentException(
                    "unsupported captured Metrics outline " + type.getName());
        }
        out.writeByte(tag);
        out.writeInt(outline.getC());
        out.writeInt(outline.getZ());
        out.writeInt(outline.getT());
        // Serialized PolygonROIs can retain statistics. Bind the values a script actually reads,
        // not only the vertices from which a fresh polygon would calculate them.
        // A valid multipart native polygon can report NaN perimeter. Identity records that
        // value; only a script returning a nonfinite Metric is rejected by result validation.
        out.writeLong(Double.doubleToRawLongBits(outline.getArea()));
        out.writeLong(Double.doubleToRawLongBits(outline.getLength()));
        out.writeLong(Double.doubleToRawLongBits(outline.getCentroidX()));
        out.writeLong(Double.doubleToRawLongBits(outline.getCentroidY()));
        out.writeLong(Double.doubleToRawLongBits(outline.getBoundsX()));
        out.writeLong(Double.doubleToRawLongBits(outline.getBoundsY()));
        out.writeLong(Double.doubleToRawLongBits(outline.getBoundsWidth()));
        out.writeLong(Double.doubleToRawLongBits(outline.getBoundsHeight()));
        var points = outline.getAllPoints();
        out.writeInt(tag == 1 ? ((PolygonROI) outline).nVertices() : points.size());
        out.writeInt(points.size());
        for (var point : points) {
            writeDouble(out, point.getX());
            writeDouble(out, point.getY());
        }
    }

    static void writeGeometry(DataOutputStream out, Geometry geometry, ImagePlane plane)
            throws IOException {
        // Do not construct a script ROI here: fallback conversion failures still belong to that
        // region's script evaluation, not to image-wide input preparation.
        out.writeByte(0);
        out.writeInt(plane.getC());
        out.writeInt(plane.getZ());
        out.writeInt(plane.getT());
        writePolygonal(out, geometry);
    }

    private static void writePolygonal(DataOutputStream out, Geometry geometry) throws IOException {
        out.writeByte(geometry instanceof Polygon ? 1 : 2);
        out.writeInt(geometry.getNumGeometries());
        for (int component = 0; component < geometry.getNumGeometries(); component++) {
            Polygon polygon = (Polygon) geometry.getGeometryN(component);
            out.writeInt(1 + polygon.getNumInteriorRing());
            writeRing(out, polygon.getExteriorRing().getCoordinateSequence());
            for (int hole = 0; hole < polygon.getNumInteriorRing(); hole++) {
                writeRing(out, polygon.getInteriorRingN(hole).getCoordinateSequence());
            }
        }
    }

    private static void writeRing(DataOutputStream out, CoordinateSequence points)
            throws IOException {
        out.writeInt(points.size());
        for (int point = 0; point < points.size(); point++) {
            writeDouble(out, points.getX(point));
            writeDouble(out, points.getY(point));
        }
    }

    private static void writeDouble(DataOutputStream out, double value) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Metrics outline value must be finite");
        }
        out.writeLong(Double.doubleToRawLongBits(value));
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static JsonObject inputMetadata(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            SourceIdentityCodec.PixelCalibration calibration,
            InferenceIdentityCodec.ModuleContentIdentity module,
            MetricScriptSnapshot script) {
        JsonObject value = new JsonObject();
        value.add("client_build", BuildIdentityCodec.toJson(clientBuild));
        value.add("calibration", SourceIdentityCodec.toJson(calibration));
        value.add("module", InferenceIdentityCodec.toJson(module));
        value.add("script", toJson(script));
        return value;
    }

    private static JsonObject toJson(MetricScriptSnapshot script) {
        JsonObject value = new JsonObject();
        value.addProperty("source", script.source());
        value.addProperty("digest", script.digest());
        JsonArray definitions = new JsonArray();
        script.schema().definitions().forEach(definition -> definitions.add(toJson(definition)));
        value.add("schema", definitions);
        return value;
    }

    private static JsonObject toJson(MetricDefinition definition) {
        JsonObject value = new JsonObject();
        value.addProperty("name", definition.name());
        value.addProperty("label", definition.label());
        value.addProperty("type", definition.type().name().toLowerCase(Locale.ROOT));
        definition.unit().ifPresent(unit -> value.addProperty("unit", unit));
        value.addProperty("decimal_places", definition.decimalPlaces());
        value.addProperty("aggregation", definition.aggregation().name().toLowerCase(Locale.ROOT));
        return value;
    }
}
