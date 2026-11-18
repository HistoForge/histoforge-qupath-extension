package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;

/**
 * Saved processing-plan details for a durable run; input evidence stores geometry and calibration.
 */
public final class ProcessingEvidenceCodec {

    private ProcessingEvidenceCodec() {}

    public sealed interface ProcessingEvidence
            permits FiniteProcessing, GlobalDecodeProcessing, CoreHaloProcessing {}

    /** A durable finite plan has no large-region profile. */
    public record FiniteProcessing(String planDigest) implements ProcessingEvidence {
        public FiniteProcessing {
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
        }
    }

    /** One complete-target decode using the captured large-region profile. */
    public record GlobalDecodeProcessing(String profileDigest, String planDigest)
            implements ProcessingEvidence {
        public GlobalDecodeProcessing {
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
        }
    }

    /** Nominal ownership core dimensions and actual context per side, in target-model pixels. */
    public record CoreHaloProcessing(
            String profileDigest,
            String planDigest,
            int coreWidthPx,
            int coreHeightPx,
            int resolvedHaloPx)
            implements ProcessingEvidence {
        public CoreHaloProcessing {
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
            coreWidthPx = CanonicalCodec.requirePositiveInt32(coreWidthPx, "core_width_px");
            coreHeightPx = CanonicalCodec.requirePositiveInt32(coreHeightPx, "core_height_px");
            resolvedHaloPx =
                    CanonicalCodec.requireNonnegativeInt32(resolvedHaloPx, "resolved_halo_px");
        }
    }

    /** Summarize a validated result plan and check that it matches the captured request. */
    public static ProcessingEvidence fromPlan(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput input,
            ModelExecutionPlanCodec.ModelExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!request.inputs().contains(input)) {
            throw new IllegalArgumentException("processing input is absent from its request");
        }
        String preprocessing =
                ModelRunPublicationCodec.preprocessingContextDigest(
                        input, request.expectedBackend());
        if (plan instanceof ModelExecutionPlanCodec.FinitePlan finite
                && request.expectedProfile().isEmpty()
                && finite.preprocessingContextDigest().equals(preprocessing)) {
            return new FiniteProcessing(finite.planDigest());
        }
        if (plan instanceof ModelExecutionPlanCodec.GlobalDecodePlan global
                && request.expectedProfile().orElse(null)
                        instanceof LargeRegionProfileCodec.GlobalDecodeProfile profile
                && global.profileDigest().equals(profile.profileDigest())
                && global.preprocessingContextDigest().equals(preprocessing)) {
            return new GlobalDecodeProcessing(global.profileDigest(), global.planDigest());
        }
        if (plan instanceof ModelExecutionPlanCodec.CoreHaloPlan core
                && request.expectedProfile().orElse(null)
                        instanceof LargeRegionProfileCodec.CoreHaloProfile profile
                && core.profileDigest().equals(profile.profileDigest())
                && core.preprocessingContextDigest().equals(preprocessing)
                && core.resolvedHaloPx() == request.haloPxOverride().orElse(profile.haloPx())) {
            // The last core may be clipped by the target. Only the captured profile owns nominal
            // dimensions.
            return new CoreHaloProcessing(
                    core.profileDigest(),
                    core.planDigest(),
                    profile.coreWidthPx(),
                    profile.coreHeightPx(),
                    core.resolvedHaloPx());
        }
        throw new IllegalArgumentException("processing plan does not match its captured request");
    }

    /** Compute the saved plan details before dispatch so storage can be reserved for them. */
    public static ProcessingEvidence expected(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput input,
            int maxUnits) {
        var plan =
                ModelExecutionPlanCodec.buildExpected(
                        request.expectedProfile(),
                        input.rasterInput().frame().transform().raster(),
                        ModelRunPublicationCodec.preprocessingContextDigest(
                                input, request.expectedBackend()),
                        maxUnits,
                        request.haloPxOverride());
        return fromPlan(request, input, plan);
    }

    public static ProcessingEvidence parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("processing evidence must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type");
        switch (type) {
            case "finite":
                fields(object, Set.of("type", "plan_digest"));
                return new FiniteProcessing(string(object, "plan_digest"));
            case "global-decode":
                fields(object, Set.of("type", "profile_digest", "plan_digest"));
                return new GlobalDecodeProcessing(
                        string(object, "profile_digest"), string(object, "plan_digest"));
            case "core-halo":
                fields(
                        object,
                        Set.of(
                                "type",
                                "profile_digest",
                                "plan_digest",
                                "core_width_px",
                                "core_height_px",
                                "resolved_halo_px"));
                return new CoreHaloProcessing(
                        string(object, "profile_digest"),
                        string(object, "plan_digest"),
                        CanonicalCodec.requirePositiveInt32(
                                object.get("core_width_px"), "core_width_px"),
                        CanonicalCodec.requirePositiveInt32(
                                object.get("core_height_px"), "core_height_px"),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("resolved_halo_px"), "resolved_halo_px"));
            default:
                throw new IllegalArgumentException("unsupported processing evidence type: " + type);
        }
    }

    public static JsonObject toJson(ProcessingEvidence evidence) {
        JsonObject value = new JsonObject();
        switch (evidence) {
            case FiniteProcessing finite -> {
                value.addProperty("type", "finite");
                value.addProperty("plan_digest", finite.planDigest());
            }
            case GlobalDecodeProcessing global -> {
                value.addProperty("type", "global-decode");
                value.addProperty("profile_digest", global.profileDigest());
                value.addProperty("plan_digest", global.planDigest());
            }
            case CoreHaloProcessing core -> {
                value.addProperty("type", "core-halo");
                value.addProperty("profile_digest", core.profileDigest());
                value.addProperty("plan_digest", core.planDigest());
                value.addProperty("core_width_px", core.coreWidthPx());
                value.addProperty("core_height_px", core.coreHeightPx());
                value.addProperty("resolved_halo_px", core.resolvedHaloPx());
            }
        }
        return value;
    }

    private static void fields(JsonObject value, Set<String> expected) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException("processing evidence has missing or unknown fields");
        }
    }

    private static String string(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
