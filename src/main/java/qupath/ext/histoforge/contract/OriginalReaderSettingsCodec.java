package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Closed reader-specific settings; scene, calibration and file identity retain their owners. */
public final class OriginalReaderSettingsCodec {
    public static final String OPENSLIDE_ADAPTER = "qupath.openslide.local";
    public static final String BIOFORMATS_ADAPTER = "qupath.bioformats.local";

    private OriginalReaderSettingsCodec() {}

    /** Native resolution corresponding exactly to one logical QuPath level. */
    public record Level(
            int resolutionLevel,
            int nativeResolutionIndex,
            SourceIdentityCodec.ScaleTranslate2D pixelsToLevelZero) {
        public Level {
            CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            CanonicalCodec.requireNonnegativeInt32(
                    nativeResolutionIndex, "native_resolution_index");
            Objects.requireNonNull(pixelsToLevelZero, "pixelsToLevelZero");
        }
    }

    public sealed interface Settings permits OpenSlide, BioFormats {
        List<Level> levels();

        String adapter();
    }

    public record OpenSlide(
            List<Level> levels,
            int cropX,
            int cropY,
            List<Integer> backgroundRgb,
            int tileWidth,
            int tileHeight)
            implements Settings {
        public OpenSlide {
            levels = normalizeLevels(levels);
            CanonicalCodec.requireNonnegativeInt32(cropX, "crop_x");
            CanonicalCodec.requireNonnegativeInt32(cropY, "crop_y");
            CanonicalCodec.requirePositiveInt32(tileWidth, "tile_width");
            CanonicalCodec.requirePositiveInt32(tileHeight, "tile_height");
            backgroundRgb = List.copyOf(backgroundRgb);
            if (backgroundRgb.size() != 3 || backgroundRgb.stream().anyMatch(v -> v < 0 || v > 255))
                throw new IllegalArgumentException(
                        "background_rgb must contain three uint8 channels");
        }

        public String adapter() {
            return OPENSLIDE_ADAPTER;
        }
    }

    /** The qualified default native RGB Bio-Formats path; arbitrary options are not accepted. */
    public record BioFormats(List<Level> levels) implements Settings {
        public BioFormats {
            levels = normalizeLevels(levels);
        }

        public String adapter() {
            return BIOFORMATS_ADAPTER;
        }
    }

    private static List<Level> normalizeLevels(List<Level> levels) {
        if (levels.isEmpty() || levels.size() > 64)
            throw new IllegalArgumentException(
                    "original reader levels must be a bounded nonempty array");
        var result =
                levels.stream().sorted(Comparator.comparingInt(Level::resolutionLevel)).toList();
        double last = 0;
        for (int index = 0; index < result.size(); index++) {
            var level = result.get(index);
            var mapping = level.pixelsToLevelZero();
            if (level.resolutionLevel() != index
                    || level.nativeResolutionIndex() != index
                    || mapping.scaleX() != mapping.scaleY()
                    || mapping.translateX() != 0
                    || mapping.translateY() != 0
                    || mapping.scaleX() <= last
                    || (index == 0 && mapping.scaleX() != 1))
                throw new IllegalArgumentException(
                        "original levels require contiguous native indices and increasing isotropic mappings");
            last = mapping.scaleX();
        }
        return result;
    }

    public static Settings parse(JsonElement value, String adapter) {
        Set<String> fields =
                switch (adapter) {
                    case OPENSLIDE_ADAPTER ->
                            Set.of(
                                    "levels",
                                    "crop_x",
                                    "crop_y",
                                    "background_rgb",
                                    "tile_width",
                                    "tile_height");
                    case BIOFORMATS_ADAPTER -> Set.of("levels");
                    default ->
                            throw new IllegalArgumentException(
                                    "original requires OpenSlide or Bio-Formats adapter");
                };
        var object = requireExactObject(value, fields, "original reader_settings");
        if (!object.get("levels").isJsonArray())
            throw new IllegalArgumentException("levels must be an array");
        var levels = new java.util.ArrayList<Level>();
        for (JsonElement item : object.getAsJsonArray("levels")) {
            var level =
                    requireExactObject(
                            item,
                            Set.of(
                                    "resolution_level",
                                    "native_resolution_index",
                                    "pixels_to_level_zero"),
                            "reader level");
            levels.add(
                    new Level(
                            CanonicalCodec.requireNonnegativeInt32(
                                    level.get("resolution_level"), "resolution_level"),
                            CanonicalCodec.requireNonnegativeInt32(
                                    level.get("native_resolution_index"),
                                    "native_resolution_index"),
                            SourceIdentityCodec.parseScaleTranslate2D(
                                    level.get("pixels_to_level_zero"))));
        }
        if (adapter.equals(BIOFORMATS_ADAPTER)) return new BioFormats(levels);
        if (!object.get("background_rgb").isJsonArray())
            throw new IllegalArgumentException("background_rgb must be an array");
        var rgb = new java.util.ArrayList<Integer>();
        for (var channel : object.getAsJsonArray("background_rgb"))
            rgb.add(CanonicalCodec.requireNonnegativeInt32(channel, "background_rgb channel"));
        return new OpenSlide(
                levels,
                CanonicalCodec.requireNonnegativeInt32(object.get("crop_x"), "crop_x"),
                CanonicalCodec.requireNonnegativeInt32(object.get("crop_y"), "crop_y"),
                rgb,
                CanonicalCodec.requirePositiveInt32(object.get("tile_width"), "tile_width"),
                CanonicalCodec.requirePositiveInt32(object.get("tile_height"), "tile_height"));
    }

    public static JsonObject toJson(Settings settings) {
        var value = new JsonObject();
        var levels = new JsonArray();
        for (var level : settings.levels()) {
            var item = new JsonObject();
            item.addProperty("resolution_level", level.resolutionLevel());
            item.addProperty("native_resolution_index", level.nativeResolutionIndex());
            item.add("pixels_to_level_zero", SourceIdentityCodec.toJson(level.pixelsToLevelZero()));
            levels.add(item);
        }
        value.add("levels", levels);
        if (settings instanceof OpenSlide openSlide) {
            value.addProperty("crop_x", openSlide.cropX());
            value.addProperty("crop_y", openSlide.cropY());
            value.addProperty("tile_width", openSlide.tileWidth());
            value.addProperty("tile_height", openSlide.tileHeight());
            var rgb = new JsonArray();
            openSlide.backgroundRgb().forEach(rgb::add);
            value.add("background_rgb", rgb);
        }
        return value;
    }
}
