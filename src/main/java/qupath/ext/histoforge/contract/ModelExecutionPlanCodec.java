package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Pure backend-generated execution-plan and processing-window contract. */
public final class ModelExecutionPlanCodec {

    private static final Set<String> WINDOW_FIELDS = Set.of("unit_id", "core_rect", "input_rect");
    private static final Set<String> FINITE_FIELDS =
            Set.of("type", "preprocessing_context_digest", "plan_digest");
    private static final Set<String> GLOBAL_FIELDS =
            Set.of("type", "profile_digest", "preprocessing_context_digest", "plan_digest");
    private static final Set<String> CORE_HALO_FIELDS =
            Set.of(
                    "type",
                    "profile_digest",
                    "preprocessing_context_digest",
                    "resolved_halo_px",
                    "windows",
                    "plan_digest");

    private ModelExecutionPlanCodec() {}

    /** One backend-generated ownership core and its containing decode input. */
    public record ProcessingWindow(
            int unitId,
            SourceIdentityCodec.IntRect coreRect,
            SourceIdentityCodec.IntRect inputRect) {
        public ProcessingWindow {
            unitId = CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            coreRect = Objects.requireNonNull(coreRect, "core_rect");
            inputRect = Objects.requireNonNull(inputRect, "input_rect");
            requireRectEndInInt32(coreRect, "core_rect");
            requireRectEndInInt32(inputRect, "input_rect");
            if (!contains(inputRect, coreRect)) {
                throw new IllegalArgumentException("input_rect must contain core_rect");
            }
        }
    }

    /** Closed backend-generated execution plan. */
    public sealed interface ModelExecutionPlan permits FinitePlan, GlobalDecodePlan, CoreHaloPlan {}

    /** One durable unit with the same complete-raster semantics as a finite call. */
    public record FinitePlan(String preprocessingContextDigest, String planDigest)
            implements ModelExecutionPlan {
        public FinitePlan {
            preprocessingContextDigest =
                    CanonicalCodec.requireSha256(
                            preprocessingContextDigest, "preprocessing_context_digest");
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
            if (!planDigest.equals(finiteDigest(preprocessingContextDigest))) {
                throw new IllegalArgumentException("plan_digest does not match plan fields");
            }
        }
    }

    /** One global-accumulator unit followed by one whole-raster decode. */
    public record GlobalDecodePlan(
            String profileDigest, String preprocessingContextDigest, String planDigest)
            implements ModelExecutionPlan {
        public GlobalDecodePlan {
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            preprocessingContextDigest =
                    CanonicalCodec.requireSha256(
                            preprocessingContextDigest, "preprocessing_context_digest");
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
            if (!planDigest.equals(globalDigest(profileDigest, preprocessingContextDigest))) {
                throw new IllegalArgumentException("plan_digest does not match plan fields");
            }
        }
    }

    /** Canonical complete nonoverlapping fixed-core cover. */
    public record CoreHaloPlan(
            String profileDigest,
            String preprocessingContextDigest,
            int resolvedHaloPx,
            List<ProcessingWindow> windows,
            String planDigest)
            implements ModelExecutionPlan {
        public CoreHaloPlan {
            profileDigest = CanonicalCodec.requireSha256(profileDigest, "profile_digest");
            preprocessingContextDigest =
                    CanonicalCodec.requireSha256(
                            preprocessingContextDigest, "preprocessing_context_digest");
            resolvedHaloPx =
                    CanonicalCodec.requireNonnegativeInt32(resolvedHaloPx, "resolved_halo_px");
            windows = normalizeWindows(windows);
            planDigest = CanonicalCodec.requireSha256(planDigest, "plan_digest");
            if (!planDigest.equals(
                    coreHaloDigest(
                            profileDigest, preprocessingContextDigest, resolvedHaloPx, windows))) {
                throw new IllegalArgumentException("plan_digest does not match plan fields");
            }
        }
    }

    /** Parse one exact backend-generated processing window. */
    public static ProcessingWindow parseProcessingWindow(JsonElement value) {
        JsonObject object = requireExactObject(value, WINDOW_FIELDS, "processing window");
        return new ProcessingWindow(
                CanonicalCodec.requireNonnegativeInt32(object.get("unit_id"), "unit_id"),
                SourceIdentityCodec.parseIntRect(object.get("core_rect")),
                SourceIdentityCodec.parseIntRect(object.get("input_rect")));
    }

    /** Parse and verify one exact backend-generated execution plan. */
    public static ModelExecutionPlan parse(JsonElement value) {
        JsonObject object = requiredObject(value, "model execution plan");
        String type = requiredString(object, "type");
        return switch (type) {
            case "finite" -> {
                requireFields(object, FINITE_FIELDS, "finite plan");
                yield new FinitePlan(
                        requiredString(object, "preprocessing_context_digest"),
                        requiredString(object, "plan_digest"));
            }
            case "global-decode" -> {
                requireFields(object, GLOBAL_FIELDS, "global-decode plan");
                yield new GlobalDecodePlan(
                        requiredString(object, "profile_digest"),
                        requiredString(object, "preprocessing_context_digest"),
                        requiredString(object, "plan_digest"));
            }
            case "core-halo" -> {
                requireFields(object, CORE_HALO_FIELDS, "core-halo plan");
                JsonElement windowsValue = object.get("windows");
                if (windowsValue == null || !windowsValue.isJsonArray()) {
                    throw new IllegalArgumentException("windows must be a nonempty array");
                }
                List<ProcessingWindow> windows = new ArrayList<>();
                for (JsonElement item : windowsValue.getAsJsonArray()) {
                    windows.add(parseProcessingWindow(item));
                }
                yield new CoreHaloPlan(
                        requiredString(object, "profile_digest"),
                        requiredString(object, "preprocessing_context_digest"),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("resolved_halo_px"), "resolved_halo_px"),
                        windows,
                        requiredString(object, "plan_digest"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported model execution plan type: " + type);
        };
    }

    /** Return the deterministic unit count for one profile and target raster. */
    public static int unitCount(
            Optional<LargeRegionProfileCodec.LargeRegionProfile> expectedProfile,
            RasterInputCodec.RasterDescriptor raster) {
        Objects.requireNonNull(expectedProfile, "expected_profile");
        Objects.requireNonNull(raster, "raster");
        if (expectedProfile.isEmpty()
                || expectedProfile.get() instanceof LargeRegionProfileCodec.GlobalDecodeProfile) {
            return 1;
        }
        var profile = (LargeRegionProfileCodec.CoreHaloProfile) expectedProfile.get();
        long columns = ((long) raster.width() + profile.coreWidthPx() - 1) / profile.coreWidthPx();
        long rows = ((long) raster.height() + profile.coreHeightPx() - 1) / profile.coreHeightPx();
        long count = Math.multiplyExact(columns, rows);
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "generated processing-unit count exceeds signed 32-bit range");
        }
        return (int) count;
    }

    /** Derive the exact backend plan for one admitted logical input. */
    public static ModelExecutionPlan buildExpected(
            Optional<LargeRegionProfileCodec.LargeRegionProfile> expectedProfile,
            RasterInputCodec.RasterDescriptor raster,
            String preprocessingContextDigest,
            int maxUnits,
            Optional<Integer> haloPxOverride) {
        Objects.requireNonNull(expectedProfile, "expected_profile");
        Objects.requireNonNull(haloPxOverride, "halo_px_override");
        haloPxOverride.ifPresent(
                value -> CanonicalCodec.requireNonnegativeInt32(value, "halo_px_override"));
        if (haloPxOverride.isPresent()
                && expectedProfile
                        .filter(LargeRegionProfileCodec.CoreHaloProfile.class::isInstance)
                        .isEmpty()) {
            throw new IllegalArgumentException("halo_px_override requires a core-halo profile");
        }
        Objects.requireNonNull(raster, "raster");
        String preprocessingDigest =
                CanonicalCodec.requireSha256(
                        preprocessingContextDigest, "preprocessing_context_digest");
        int unitLimit = CanonicalCodec.requirePositiveInt32(maxUnits, "max_units");
        if (unitCount(expectedProfile, raster) > unitLimit) {
            throw new IllegalArgumentException("generated model execution plan exceeds max_units");
        }
        if (expectedProfile.isEmpty()) {
            return new FinitePlan(preprocessingDigest, finiteDigest(preprocessingDigest));
        }
        if (expectedProfile.get() instanceof LargeRegionProfileCodec.GlobalDecodeProfile global) {
            return new GlobalDecodePlan(
                    global.profileDigest(),
                    preprocessingDigest,
                    globalDigest(global.profileDigest(), preprocessingDigest));
        }

        var profile = (LargeRegionProfileCodec.CoreHaloProfile) expectedProfile.get();
        int resolvedHaloPx = haloPxOverride.orElse(profile.haloPx());
        List<ProcessingWindow> windows = new ArrayList<>();
        forEachCoreHaloWindow(profile, raster, resolvedHaloPx, windows::add);
        String digest =
                coreHaloDigest(
                        profile.profileDigest(), preprocessingDigest, resolvedHaloPx, windows);
        return new CoreHaloPlan(
                profile.profileDigest(), preprocessingDigest, resolvedHaloPx, windows, digest);
    }

    /**
     * Check actual allocations after the request and its unit count are admitted.
     *
     * <p>The same clipped windows feed plan generation and resource admission, without building or
     * hashing a second complete plan before source access.
     */
    public static void validateProcessingRasterLimits(
            Optional<LargeRegionProfileCodec.LargeRegionProfile> expectedProfile,
            RasterInputCodec.RasterDescriptor raster,
            Optional<Integer> haloPxOverride,
            ExecutionLimitsCodec.TargetRasterLimits processingLimits) {
        if (expectedProfile.isEmpty()
                || expectedProfile.get() instanceof LargeRegionProfileCodec.GlobalDecodeProfile) {
            if (!ExecutionLimitsCodec.fitsTargetRaster(raster, processingLimits)) {
                throw new IllegalArgumentException(
                        "model run processing raster exceeds processing_raster");
            }
            return;
        }
        var profile = (LargeRegionProfileCodec.CoreHaloProfile) expectedProfile.get();
        forEachCoreHaloWindow(
                profile,
                raster,
                haloPxOverride.orElse(profile.haloPx()),
                window -> {
                    var input = window.inputRect();
                    if (!ExecutionLimitsCodec.fitsTargetRaster(
                            input.width(), input.height(), processingLimits)) {
                        throw new IllegalArgumentException(
                                "model run processing window exceeds processing_raster");
                    }
                });
    }

    private static void forEachCoreHaloWindow(
            LargeRegionProfileCodec.CoreHaloProfile profile,
            RasterInputCodec.RasterDescriptor raster,
            int resolvedHaloPx,
            Consumer<ProcessingWindow> consumeWindow) {
        int unitId = 0;
        int y = 0;
        while (y < raster.height()) {
            int coreHeight = Math.min(profile.coreHeightPx(), raster.height() - y);
            int x = 0;
            while (x < raster.width()) {
                int coreWidth = Math.min(profile.coreWidthPx(), raster.width() - x);
                int inputX = (int) Math.max(0L, (long) x - resolvedHaloPx);
                int inputY = (int) Math.max(0L, (long) y - resolvedHaloPx);
                int inputEndX =
                        (int) Math.min(raster.width(), (long) x + coreWidth + resolvedHaloPx);
                int inputEndY =
                        (int) Math.min(raster.height(), (long) y + coreHeight + resolvedHaloPx);
                consumeWindow.accept(
                        new ProcessingWindow(
                                unitId++,
                                new SourceIdentityCodec.IntRect(x, y, coreWidth, coreHeight),
                                new SourceIdentityCodec.IntRect(
                                        inputX, inputY, inputEndX - inputX, inputEndY - inputY)));
                x = Math.addExact(x, coreWidth);
            }
            y = Math.addExact(y, coreHeight);
        }
    }

    /** Return the number of stable unit IDs in one validated plan. */
    public static int unitCount(ModelExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return plan instanceof CoreHaloPlan core ? core.windows().size() : 1;
    }

    /** Encode one processing window using canonical scalar and rectangle shapes. */
    public static JsonObject toJson(ProcessingWindow window) {
        JsonObject value = new JsonObject();
        value.addProperty("unit_id", window.unitId());
        value.add("core_rect", SourceIdentityCodec.toJson(window.coreRect()));
        value.add("input_rect", SourceIdentityCodec.toJson(window.inputRect()));
        return value;
    }

    /** Encode one complete execution plan using its closed variant shape. */
    public static JsonObject toJson(ModelExecutionPlan plan) {
        JsonObject value;
        if (plan instanceof FinitePlan finite) {
            value = finitePreimage(finite.preprocessingContextDigest());
            value.addProperty("plan_digest", finite.planDigest());
        } else if (plan instanceof GlobalDecodePlan global) {
            value = globalPreimage(global.profileDigest(), global.preprocessingContextDigest());
            value.addProperty("plan_digest", global.planDigest());
        } else if (plan instanceof CoreHaloPlan core) {
            value =
                    coreHaloPreimage(
                            core.profileDigest(),
                            core.preprocessingContextDigest(),
                            core.resolvedHaloPx(),
                            core.windows());
            value.addProperty("plan_digest", core.planDigest());
        } else {
            throw new IllegalArgumentException("unsupported model execution plan");
        }
        return value;
    }

    private static String finiteDigest(String preprocessingContextDigest) {
        return CanonicalCodec.canonicalDigest(finitePreimage(preprocessingContextDigest));
    }

    private static JsonObject finitePreimage(String preprocessingContextDigest) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "finite");
        value.addProperty("preprocessing_context_digest", preprocessingContextDigest);
        return value;
    }

    private static String globalDigest(String profileDigest, String preprocessingContextDigest) {
        return CanonicalCodec.canonicalDigest(
                globalPreimage(profileDigest, preprocessingContextDigest));
    }

    private static JsonObject globalPreimage(
            String profileDigest, String preprocessingContextDigest) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "global-decode");
        value.addProperty("profile_digest", profileDigest);
        value.addProperty("preprocessing_context_digest", preprocessingContextDigest);
        return value;
    }

    private static String coreHaloDigest(
            String profileDigest,
            String preprocessingContextDigest,
            int resolvedHaloPx,
            List<ProcessingWindow> windows) {
        return CanonicalCodec.canonicalDigest(
                coreHaloPreimage(
                        profileDigest, preprocessingContextDigest, resolvedHaloPx, windows));
    }

    private static JsonObject coreHaloPreimage(
            String profileDigest,
            String preprocessingContextDigest,
            int resolvedHaloPx,
            List<ProcessingWindow> windows) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "core-halo");
        value.addProperty("profile_digest", profileDigest);
        value.addProperty("preprocessing_context_digest", preprocessingContextDigest);
        // Clipping can produce identical windows for different halo choices.
        value.addProperty("resolved_halo_px", resolvedHaloPx);
        JsonArray encodedWindows = new JsonArray();
        windows.forEach(window -> encodedWindows.add(toJson(window)));
        value.add("windows", encodedWindows);
        return value;
    }

    private static List<ProcessingWindow> normalizeWindows(List<ProcessingWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("windows must be a nonempty array");
        }
        List<ProcessingWindow> result = new ArrayList<>(windows.size());
        for (int index = 0; index < windows.size(); index++) {
            ProcessingWindow window = Objects.requireNonNull(windows.get(index), "window");
            if (window.unitId() != index) {
                throw new IllegalArgumentException(
                        "windows must use contiguous unit_id order starting at zero");
            }
            result.add(window);
        }
        requireCompleteCoreCover(result);
        return List.copyOf(result);
    }

    private static void requireCompleteCoreCover(List<ProcessingWindow> windows) {
        Map<Integer, Integer> rowHeights = new HashMap<>();
        Map<Integer, List<SourceIdentityCodec.IntRect>> rows = new HashMap<>();
        int targetWidth = 0;
        int targetHeight = 0;
        for (ProcessingWindow window : windows) {
            SourceIdentityCodec.IntRect core = window.coreRect();
            int coreEndX = Math.addExact(core.x(), core.width());
            int coreEndY = Math.addExact(core.y(), core.height());
            targetWidth = Math.max(targetWidth, coreEndX);
            targetHeight = Math.max(targetHeight, coreEndY);
            Integer previousHeight = rowHeights.putIfAbsent(core.y(), core.height());
            if (previousHeight != null && previousHeight != core.height()) {
                throw new IllegalArgumentException("cores sharing a row must have the same height");
            }
            rows.computeIfAbsent(core.y(), ignored -> new ArrayList<>()).add(core);
        }

        List<Integer> rowStarts = new ArrayList<>(rows.keySet());
        rowStarts.sort(Integer::compareTo);
        List<ColumnSpan> expectedColumns = null;
        int canonicalIndex = 0;
        int nextY = 0;
        for (int rowY : rowStarts) {
            int rowHeight = rowHeights.get(rowY);
            if (rowY != nextY) {
                throw new IllegalArgumentException(
                        "core rectangles must cover rows without gaps or overlap");
            }
            List<SourceIdentityCodec.IntRect> cores = rows.get(rowY);
            cores.sort(Comparator.comparingInt(SourceIdentityCodec.IntRect::x));
            List<ColumnSpan> columns =
                    cores.stream().map(core -> new ColumnSpan(core.x(), core.width())).toList();
            if (expectedColumns == null) {
                expectedColumns = columns;
            } else if (!columns.equals(expectedColumns)) {
                throw new IllegalArgumentException(
                        "every core row must use the same column partition");
            }
            int nextX = 0;
            for (SourceIdentityCodec.IntRect core : cores) {
                if (core.x() != nextX) {
                    throw new IllegalArgumentException(
                            "core rectangles must cover columns without gaps or overlap");
                }
                nextX = Math.addExact(nextX, core.width());
                if (!windows.get(canonicalIndex).coreRect().equals(core)) {
                    throw new IllegalArgumentException(
                            "windows must be ordered row-major by core_rect");
                }
                canonicalIndex++;
            }
            if (nextX != targetWidth) {
                throw new IllegalArgumentException(
                        "every core row must cover the same target width");
            }
            nextY = Math.addExact(nextY, rowHeight);
        }
        if (nextY != targetHeight) {
            throw new IllegalArgumentException("core rectangles must cover the target height");
        }

        for (ProcessingWindow window : windows) {
            SourceIdentityCodec.IntRect input = window.inputRect();
            if (Math.addExact(input.x(), input.width()) > targetWidth
                    || Math.addExact(input.y(), input.height()) > targetHeight) {
                throw new IllegalArgumentException(
                        "input_rect must stay inside the logical target rectangle");
            }
        }
    }

    private record ColumnSpan(int x, int width) {}

    private static boolean contains(
            SourceIdentityCodec.IntRect outer, SourceIdentityCodec.IntRect inner) {
        return outer.x() <= inner.x()
                && outer.y() <= inner.y()
                && (long) outer.x() + outer.width() >= (long) inner.x() + inner.width()
                && (long) outer.y() + outer.height() >= (long) inner.y() + inner.height();
    }

    private static void requireRectEndInInt32(SourceIdentityCodec.IntRect rectangle, String path) {
        if ((long) rectangle.x() + rectangle.width() > Integer.MAX_VALUE
                || (long) rectangle.y() + rectangle.height() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    path + " endpoint must fit a signed 32-bit target raster");
        }
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
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
