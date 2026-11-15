package qupath.ext.histoforge.spatial;

import static qupath.ext.histoforge.contract.StrictJson.requireFields;
import static qupath.ext.histoforge.contract.StrictJson.requireObject;
import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;

/** Saves the chosen source representation before pixel reading or file encoding starts. */
public final class SourceArtifactPlanCodec {

    private static final Set<String> ORIGINAL_FIELDS =
            Set.of(
                    "type",
                    "total_bytes",
                    "pyramid_series_index",
                    "level_zero_pixels_to_slide",
                    "required_levels",
                    "container",
                    "entry_path",
                    "source_adapter_id",
                    "reader_settings");
    private static final Set<String> DERIVED_FIELDS =
            Set.of(
                    "type",
                    "container",
                    "source_resolution_level",
                    "plane",
                    "source_rect_at_level",
                    "artifact_pixels_to_slide",
                    "conservative_artifact_bytes");
    private static final Set<String> INCREMENTAL_FIELDS =
            Set.of(
                    "type",
                    "source_level",
                    "source_level_pixels_to_level_zero",
                    "plane",
                    "source_rect_at_level",
                    "level_zero_pixels_to_slide");

    private SourceArtifactPlanCodec() {}

    public static SourceArtifactPlan parse(JsonElement value) {
        JsonObject object = requireObject(value, "source plan");
        return switch (requiredString(object, "type")) {
            case "original" -> {
                requireFields(object, ORIGINAL_FIELDS, "original source plan");
                JsonElement levels = object.get("required_levels");
                if (!levels.isJsonArray()) {
                    throw new IllegalArgumentException("required_levels must be an array");
                }
                var required = new ArrayList<SourceArtifactCodec.SourceLevelRequirement>();
                levels.getAsJsonArray()
                        .forEach(
                                level ->
                                        required.add(
                                                SourceArtifactCodec.parseLevelRequirement(level)));
                yield new OriginalArtifactPlan(
                        CanonicalCodec.requirePositiveSafeInteger(
                                object.get("total_bytes"), "total_bytes"),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("pyramid_series_index"), "pyramid_series_index"),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("level_zero_pixels_to_slide")),
                        required,
                        SourceArtifactCodec.parseContainer(requiredString(object, "container")),
                        requiredString(object, "entry_path"),
                        OriginalReaderSettingsCodec.parse(
                                object.get("reader_settings"),
                                requiredString(object, "source_adapter_id")));
            }
            case "derived" -> {
                requireFields(object, DERIVED_FIELDS, "derived source plan");
                yield new DerivedArtifactPlan(
                        SourceArtifactCodec.parseContainer(requiredString(object, "container")),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("source_resolution_level"), "source_resolution_level"),
                        SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                        SourceIdentityCodec.parseIntRect(object.get("source_rect_at_level")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("artifact_pixels_to_slide")),
                        CanonicalCodec.requirePositiveSafeInteger(
                                object.get("conservative_artifact_bytes"),
                                "conservative_artifact_bytes"));
            }
            case "incremental" -> {
                requireFields(object, INCREMENTAL_FIELDS, "incremental source plan");
                var level = SourceArtifactCodec.parseLevelRequirement(object.get("source_level"));
                yield new IncrementalSourcePlan(
                        new SourceInputPlan.PinnedSourceLevel(
                                level.seriesIndex(),
                                level.resolutionLevel(),
                                new SourceViewGeometry.Dimensions(level.width(), level.height()),
                                SourceIdentityCodec.parseScaleTranslate2D(
                                        object.get("source_level_pixels_to_level_zero"))),
                        SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                        SourceIdentityCodec.parseIntRect(object.get("source_rect_at_level")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("level_zero_pixels_to_slide")));
            }
            default -> throw new IllegalArgumentException("unsupported source plan type");
        };
    }

    public static JsonObject toJson(SourceArtifactPlan plan) {
        JsonObject value = new JsonObject();
        switch (plan) {
            case OriginalArtifactPlan original -> {
                value.addProperty("type", "original");
                value.addProperty("total_bytes", original.totalBytes());
                value.addProperty("pyramid_series_index", original.pyramidSeriesIndex());
                value.add(
                        "level_zero_pixels_to_slide",
                        SourceIdentityCodec.toJson(original.levelZeroPixelsToSlide()));
                JsonArray levels = new JsonArray();
                original.requiredLevels()
                        .forEach(level -> levels.add(SourceArtifactCodec.toJson(level)));
                value.add("required_levels", levels);
                value.addProperty("container", original.container().name());
                value.addProperty("entry_path", original.entryPath());
                value.addProperty("source_adapter_id", original.readerSettings().adapter());
                value.add(
                        "reader_settings",
                        OriginalReaderSettingsCodec.toJson(original.readerSettings()));
            }
            case DerivedArtifactPlan derived -> {
                value.addProperty("type", "derived");
                value.addProperty("container", derived.container().name());
                value.addProperty("source_resolution_level", derived.sourceResolutionLevel());
                value.add("plane", SourceIdentityCodec.toJson(derived.plane()));
                value.add(
                        "source_rect_at_level",
                        SourceIdentityCodec.toJson(derived.sourceRectAtLevel()));
                value.add(
                        "artifact_pixels_to_slide",
                        SourceIdentityCodec.toJson(derived.artifactPixelsToSlide()));
                value.addProperty(
                        "conservative_artifact_bytes", derived.conservativeArtifactBytes());
            }
            case IncrementalSourcePlan incremental -> {
                value.addProperty("type", "incremental");
                var level = incremental.sourceLevel();
                value.add(
                        "source_level",
                        SourceArtifactCodec.toJson(
                                new SourceArtifactCodec.SourceLevelRequirement(
                                        level.seriesIndex(),
                                        level.resolutionLevel(),
                                        level.dimensions().width(),
                                        level.dimensions().height())));
                value.add(
                        "source_level_pixels_to_level_zero",
                        SourceIdentityCodec.toJson(level.pixelsToLevelZero()));
                value.add("plane", SourceIdentityCodec.toJson(incremental.plane()));
                value.add(
                        "source_rect_at_level",
                        SourceIdentityCodec.toJson(incremental.sourceRectAtLevel()));
                value.add(
                        "level_zero_pixels_to_slide",
                        SourceIdentityCodec.toJson(incremental.levelZeroPixelsToSlide()));
            }
        }
        return value;
    }
}
