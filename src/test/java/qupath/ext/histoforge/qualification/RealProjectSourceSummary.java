package qupath.ext.histoforge.qualification;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Privacy-safe dimensions and calibration selected from persisted server metadata. */
record RealProjectSourceSummary(
        Integer widthPx,
        Integer heightPx,
        Integer pyramidLevels,
        Double mppX,
        Double mppY,
        String calibrationState) {

    private static final String MICROMETER = "µm";

    static RealProjectSourceSummary fromProjectImage(JsonObject image) throws IOException {
        JsonObject serverBuilder = optionalObject(image, "serverBuilder");
        JsonObject metadata =
                serverBuilder == null ? null : optionalObject(serverBuilder, "metadata");
        if (metadata == null) {
            return new RealProjectSourceSummary(null, null, null, null, null, "missing");
        }

        Integer width = optionalPositiveInt(metadata, "width");
        Integer height = optionalPositiveInt(metadata, "height");
        Integer levels = optionalArraySize(metadata, "levels");
        JsonObject calibration = optionalObject(metadata, "pixelCalibration");
        if (calibration == null) {
            return new RealProjectSourceSummary(width, height, levels, null, null, "missing");
        }
        JsonObject xAxis = optionalObject(calibration, "pixelWidth");
        JsonObject yAxis = optionalObject(calibration, "pixelHeight");
        if (!completeAxis(xAxis) || !completeAxis(yAxis)) {
            return new RealProjectSourceSummary(width, height, levels, null, null, "missing");
        }

        double x = finiteValue(xAxis, "pixelWidth");
        double y = finiteValue(yAxis, "pixelHeight");
        String state;
        if (!MICROMETER.equals(unit(xAxis)) || !MICROMETER.equals(unit(yAxis))) {
            state = "unsupported-unit";
        } else if (!(x > 0.0) || !(y > 0.0)) {
            state = "nonpositive";
        } else if (x == y) {
            state = "square-positive";
        } else {
            state = "anisotropic-positive";
        }
        return new RealProjectSourceSummary(width, height, levels, x, y, state);
    }

    static JsonObject totals(Collection<RealProjectSourceSummary> sources) {
        Map<String, Long> states = new TreeMap<>();
        Map<String, Long> dimensions = new TreeMap<>();
        Map<String, Long> levelCounts = new TreeMap<>();
        Map<String, Long> calibrations = new TreeMap<>();
        for (RealProjectSourceSummary source : sources) {
            increment(states, source.calibrationState);
            if (source.widthPx != null && source.heightPx != null) {
                increment(dimensions, source.widthPx + "x" + source.heightPx);
            }
            if (source.pyramidLevels != null) {
                increment(levelCounts, Integer.toString(source.pyramidLevels));
            }
            if (source.mppX != null
                    && source.mppY != null
                    && (source.calibrationState.equals("square-positive")
                            || source.calibrationState.equals("anisotropic-positive"))) {
                increment(
                        calibrations,
                        Double.toString(source.mppX) + "x" + Double.toString(source.mppY));
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("entries", sources.size());
        result.add("calibration_states", mapJson(states));
        result.add("dimensions_px", mapJson(dimensions));
        result.add("pyramid_level_counts", mapJson(levelCounts));
        result.add("positive_calibrations_mpp", mapJson(calibrations));
        return result;
    }

    private static JsonObject optionalObject(JsonObject parent, String name) throws IOException {
        if (missing(parent, name)) {
            return null;
        }
        JsonElement value = parent.get(name);
        if (!value.isJsonObject()) {
            throw new IOException("project image metadata " + name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static Integer optionalPositiveInt(JsonObject parent, String name) throws IOException {
        if (missing(parent, name)) {
            return null;
        }
        try {
            return CanonicalCodec.requirePositiveInt32(parent.get(name), name);
        } catch (IllegalArgumentException error) {
            throw new IOException("project image metadata " + name + " is invalid", error);
        }
    }

    private static Integer optionalArraySize(JsonObject parent, String name) throws IOException {
        if (missing(parent, name)) {
            return null;
        }
        JsonElement value = parent.get(name);
        if (!value.isJsonArray()) {
            throw new IOException("project image metadata " + name + " must be an array");
        }
        return value.getAsJsonArray().size();
    }

    private static boolean completeAxis(JsonObject axis) {
        return axis != null && !missing(axis, "value") && !missing(axis, "unit");
    }

    private static double finiteValue(JsonObject axis, String name) throws IOException {
        try {
            return CanonicalCodec.requireFiniteDouble(axis.get("value"), name + ".value");
        } catch (IllegalArgumentException error) {
            throw new IOException("project calibration " + name + " is invalid", error);
        }
    }

    private static String unit(JsonObject axis) throws IOException {
        JsonElement value = axis.get("unit");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("project calibration unit must be a string");
        }
        return value.getAsString();
    }

    private static boolean missing(JsonObject parent, String name) {
        return !parent.has(name) || parent.get(name).isJsonNull();
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Math::addExact);
    }

    private static JsonObject mapJson(Map<String, Long> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }
}
