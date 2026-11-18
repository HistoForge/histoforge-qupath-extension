package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Resolved large-region settings and the runner's recorded validation evidence. */
public final class LargeRegionProfileCodec {

    private static final Set<String> GLOBAL_FIELDS =
            Set.of("type", "profile_digest", "validation_evidence_digest");
    private static final Set<String> CORE_HALO_FIELDS =
            Set.of(
                    "type",
                    "core_width_px",
                    "core_height_px",
                    "halo_px",
                    "profile_digest",
                    "validation_evidence_digest");

    private LargeRegionProfileCodec() {}

    /** Supported strategy with resolved settings and a reference to recorded validation. */
    public sealed interface LargeRegionProfile permits GlobalDecodeProfile, CoreHaloProfile {}

    /** Accumulate dense fields over the complete target raster before one decode. */
    public record GlobalDecodeProfile(String profileDigest, String validationEvidenceDigest)
            implements LargeRegionProfile {
        public GlobalDecodeProfile {
            validationEvidenceDigest =
                    CanonicalCodec.requireSha256(
                            validationEvidenceDigest, "validation_evidence_digest");
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            if (!profileDigest.equals(globalDigest(validationEvidenceDigest))) {
                throw new IllegalArgumentException("profile_digest does not match profile fields");
            }
        }
    }

    /** Decode fixed nonoverlapping ownership cores with a fixed context halo. */
    public record CoreHaloProfile(
            int coreWidthPx,
            int coreHeightPx,
            int haloPx,
            String profileDigest,
            String validationEvidenceDigest)
            implements LargeRegionProfile {
        public CoreHaloProfile {
            coreWidthPx = CanonicalCodec.requirePositiveInt32(coreWidthPx, "core_width_px");
            coreHeightPx = CanonicalCodec.requirePositiveInt32(coreHeightPx, "core_height_px");
            haloPx = CanonicalCodec.requireNonnegativeInt32(haloPx, "halo_px");
            validationEvidenceDigest =
                    CanonicalCodec.requireSha256(
                            validationEvidenceDigest, "validation_evidence_digest");
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            if (!profileDigest.equals(
                    coreHaloDigest(coreWidthPx, coreHeightPx, haloPx, validationEvidenceDigest))) {
                throw new IllegalArgumentException("profile_digest does not match profile fields");
            }
        }
    }

    /** Parse and verify one exact large-region profile. */
    public static LargeRegionProfile parse(JsonElement value) {
        JsonObject object = requiredObject(value, "large-region profile");
        String type = requiredString(object, "type");
        return switch (type) {
            case "global-decode" -> {
                requireFields(object, GLOBAL_FIELDS, "global-decode profile");
                yield new GlobalDecodeProfile(
                        requiredString(object, "profile_digest"),
                        requiredString(object, "validation_evidence_digest"));
            }
            case "core-halo" -> {
                requireFields(object, CORE_HALO_FIELDS, "core-halo profile");
                yield new CoreHaloProfile(
                        CanonicalCodec.requirePositiveInt32(
                                object.get("core_width_px"), "core_width_px"),
                        CanonicalCodec.requirePositiveInt32(
                                object.get("core_height_px"), "core_height_px"),
                        CanonicalCodec.requireNonnegativeInt32(object.get("halo_px"), "halo_px"),
                        requiredString(object, "profile_digest"),
                        requiredString(object, "validation_evidence_digest"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported large-region profile type: " + type);
        };
    }

    /** Encode one profile using exact lower-snake fields and lower-kebab tags. */
    public static JsonObject toJson(LargeRegionProfile profile) {
        JsonObject value;
        if (profile instanceof GlobalDecodeProfile global) {
            value = globalPreimage(global.validationEvidenceDigest());
            value.addProperty("profile_digest", global.profileDigest());
        } else if (profile instanceof CoreHaloProfile core) {
            value =
                    coreHaloPreimage(
                            core.coreWidthPx(),
                            core.coreHeightPx(),
                            core.haloPx(),
                            core.validationEvidenceDigest());
            value.addProperty("profile_digest", core.profileDigest());
        } else {
            throw new IllegalArgumentException("unsupported large-region profile");
        }
        return value;
    }

    private static String globalDigest(String validationEvidenceDigest) {
        return CanonicalCodec.canonicalDigest(globalPreimage(validationEvidenceDigest));
    }

    /** Check that advertised core/halo defaults came from this model's validated recipe. */
    public static void requireRecipeSettings(LargeRegionProfile profile, JsonObject modelInput) {
        if (!(profile instanceof CoreHaloProfile core)) {
            return;
        }
        JsonObject settings = requiredObject(modelInput.get("largeRegion"), "input.largeRegion");
        if (core.coreWidthPx()
                        != CanonicalCodec.requirePositiveInt32(
                                settings.get("coreWidthModelPx"), "coreWidthModelPx")
                || core.coreHeightPx()
                        != CanonicalCodec.requirePositiveInt32(
                                settings.get("coreHeightModelPx"), "coreHeightModelPx")
                || core.haloPx()
                        != CanonicalCodec.requireNonnegativeInt32(
                                settings.get("haloPerSideModelPx"), "haloPerSideModelPx")) {
            throw new IllegalArgumentException(
                    "core-halo profile does not match input.largeRegion");
        }
    }

    private static JsonObject globalPreimage(String validationEvidenceDigest) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "global-decode");
        value.addProperty("validation_evidence_digest", validationEvidenceDigest);
        return value;
    }

    private static String coreHaloDigest(
            int coreWidthPx, int coreHeightPx, int haloPx, String validationEvidenceDigest) {
        return CanonicalCodec.canonicalDigest(
                coreHaloPreimage(coreWidthPx, coreHeightPx, haloPx, validationEvidenceDigest));
    }

    private static JsonObject coreHaloPreimage(
            int coreWidthPx, int coreHeightPx, int haloPx, String validationEvidenceDigest) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "core-halo");
        value.addProperty("core_width_px", coreWidthPx);
        value.addProperty("core_height_px", coreHeightPx);
        value.addProperty("halo_px", haloPx);
        value.addProperty("validation_evidence_digest", validationEvidenceDigest);
        return value;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
