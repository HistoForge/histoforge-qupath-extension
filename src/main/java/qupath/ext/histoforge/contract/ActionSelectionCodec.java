package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.modules.ModuleContract;

/** Canonical explicit user-action and target-selection contracts. */
public final class ActionSelectionCodec {

    private static final Set<String> ROI_REGIONS_FIELDS = Set.of("type", "roi_ids");
    private static final Set<String> SLIDE_REGION_FIELDS = Set.of("type", "region");
    private static final Set<String> FULL_SLIDE_FIELDS = Set.of("type");
    private static final Set<String> ANNOTATION_UNION_FIELDS = Set.of("type", "annotation_ids");
    private static final Set<String> ROI_TARGET_FIELDS = Set.of("type", "roi_id");
    private static final Set<String> PARENT_TARGET_FIELDS = Set.of("type", "region", "parent_id");
    private static final Set<String> SLIDE_TARGET_FIELDS = Set.of("type");
    private static final Set<String> TARGET_SELECTION_FIELDS = Set.of("selectors");
    private static final Set<String> STEP_TARGETS_FIELDS = Set.of("step_id", "targets");
    private static final Set<String> ALL_MODELS_FIELDS = Set.of("type", "regions");
    private static final Set<String> SELECTED_MODELS_FIELDS = Set.of("type", "regions", "steps");
    private static final Set<String> MODEL_ACTION_FIELDS =
            Set.of("type", "work", "remove_overlapping_same_class_duplicates");
    private static final Set<String> METRICS_ACTION_FIELDS = Set.of("type", "targets");

    private ActionSelectionCodec() {}

    /** Captured full-slide or annotation-union selector. */
    public sealed interface SlideRegionSelection permits FullSlide, AnnotationUnion {}

    /** Select the complete level-zero slide rectangle. */
    public record FullSlide() implements SlideRegionSelection {}

    /** Select the normalized union of exact persistent annotations. */
    public record AnnotationUnion(Set<String> annotationIds) implements SlideRegionSelection {
        public AnnotationUnion {
            annotationIds = canonicalUuidSet(annotationIds, "annotation_ids");
        }
    }

    /** One action-level ROI-set or slide-region owner. */
    public sealed interface RegionSelection permits RoiRegions, SlideRegion {}

    /** Select one or more persistent HistoForge ROIs. */
    public record RoiRegions(Set<String> roiIds) implements RegionSelection {
        public RoiRegions {
            roiIds = canonicalUuidSet(roiIds, "roi_ids");
        }
    }

    /** Select one captured full-slide or annotation-union region. */
    public record SlideRegion(SlideRegionSelection region) implements RegionSelection {
        public SlideRegion {
            region = Objects.requireNonNull(region, "region");
        }
    }

    /** One recipe-resolved ROI, exact parent, or slide target marker. */
    public sealed interface TargetSelector permits RoiTarget, ParentTarget, SlideTarget {}

    /** Select one persistent HistoForge ROI. */
    public record RoiTarget(String roiId) implements TargetSelector {
        public RoiTarget {
            roiId = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        }
    }

    /** Select one exact source parent inside one logical region. */
    public record ParentTarget(ExecutionScopeCodec.RegionKey region, String parentId)
            implements TargetSelector {
        public ParentTarget {
            region = Objects.requireNonNull(region, "region");
            parentId = CanonicalCodec.requireCanonicalUuid(parentId, "parent_id");
        }
    }

    /** Select the enclosing captured slide region. */
    public record SlideTarget() implements TargetSelector {}

    /** Canonically ordered, duplicate-free, nonempty target selectors for one step. */
    public record TargetSelection(List<TargetSelector> selectors) {
        public TargetSelection {
            if (selectors == null || selectors.isEmpty()) {
                throw new IllegalArgumentException("selectors must be a nonempty array");
            }
            List<TargetSelector> normalized = new ArrayList<>(selectors.size());
            Set<TargetSelector> unique = new HashSet<>();
            for (TargetSelector selector : selectors) {
                TargetSelector item = Objects.requireNonNull(selector, "selector");
                if (!unique.add(item)) {
                    throw new IllegalArgumentException("selectors must not contain duplicates");
                }
                normalized.add(item);
            }
            normalized.sort(
                    (left, right) ->
                            Arrays.compareUnsigned(
                                    canonicalSelectorBytes(left), canonicalSelectorBytes(right)));
            selectors = List.copyOf(normalized);
        }
    }

    /** One selected model step and its explicit targets. */
    public record StepTargets(String stepId, TargetSelection targets) {
        public StepTargets {
            stepId = ModuleContract.requireModelId(stepId, "step_id");
            targets = Objects.requireNonNull(targets, "targets");
        }
    }

    /** Closed all-model or selected-model work request. */
    public sealed interface ModelWorkSelection permits AllModels, SelectedModels {}

    /** Apply every recipe model to one shared region selection. */
    public record AllModels(RegionSelection regions) implements ModelWorkSelection {
        public AllModels {
            regions = Objects.requireNonNull(regions, "regions");
        }
    }

    /** Apply explicitly named model steps within one shared region selection. */
    public record SelectedModels(RegionSelection regions, List<StepTargets> steps)
            implements ModelWorkSelection {
        public SelectedModels {
            regions = Objects.requireNonNull(regions, "regions");
            if (steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException("steps must be a nonempty array");
            }
            List<StepTargets> normalized = new ArrayList<>(steps.size());
            Set<String> stepIds = new HashSet<>();
            for (StepTargets step : steps) {
                StepTargets item = Objects.requireNonNull(step, "step");
                if (!stepIds.add(item.stepId())) {
                    throw new IllegalArgumentException(
                            "steps must not contain duplicate step_id values");
                }
                requireTargetsWithinRegions(item.targets(), regions);
                normalized.add(item);
            }
            steps = List.copyOf(normalized);
        }
    }

    /** Closed explicit user intent. */
    public sealed interface ImageAction permits Fill, Redo, Metrics {}

    /** Fill absent selected output while preserving existing attributed output. */
    public record Fill(ModelWorkSelection work, boolean removeOverlappingSameClassDuplicates)
            implements ImageAction {
        public Fill {
            work = Objects.requireNonNull(work, "work");
        }
    }

    /** Stage unconditional replacement of selected output and its dependency closure. */
    public record Redo(ModelWorkSelection work, boolean removeOverlappingSameClassDuplicates)
            implements ImageAction {
        public Redo {
            work = Objects.requireNonNull(work, "work");
        }
    }

    /** Recompute metrics for explicit logical regions without model inference. */
    public record Metrics(RegionSelection targets) implements ImageAction {
        public Metrics {
            targets = Objects.requireNonNull(targets, "targets");
        }
    }

    /** Parse one exact ROI-set or slide-region owner. */
    public static RegionSelection parseRegionSelection(JsonElement value) {
        JsonObject object = requiredObject(value, "region selection");
        String type = requiredString(object, "type");
        return switch (type) {
            case "roi-regions" -> {
                requireFields(object, ROI_REGIONS_FIELDS, "ROI region selection");
                yield new RoiRegions(parseUuidSet(object.get("roi_ids"), "roi_ids"));
            }
            case "slide-region" -> {
                requireFields(object, SLIDE_REGION_FIELDS, "slide region selection");
                yield new SlideRegion(parseSlideRegionSelection(object.get("region")));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported region selection type: " + type);
        };
    }

    /** Parse one exact full-slide or annotation-union selector. */
    public static SlideRegionSelection parseSlideRegionSelection(JsonElement value) {
        JsonObject object = requiredObject(value, "slide region selector");
        String type = requiredString(object, "type");
        return switch (type) {
            case "full-slide" -> {
                requireFields(object, FULL_SLIDE_FIELDS, "full-slide selector");
                yield new FullSlide();
            }
            case "annotation-union" -> {
                requireFields(object, ANNOTATION_UNION_FIELDS, "annotation-union selector");
                yield new AnnotationUnion(
                        parseUuidSet(object.get("annotation_ids"), "annotation_ids"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported slide region selector type: " + type);
        };
    }

    /** Parse one exact ROI, parent, or slide target. */
    public static TargetSelector parseTargetSelector(JsonElement value) {
        JsonObject object = requiredObject(value, "target selector");
        String type = requiredString(object, "type");
        return switch (type) {
            case "roi-target" -> {
                requireFields(object, ROI_TARGET_FIELDS, "ROI target");
                yield new RoiTarget(requiredString(object, "roi_id"));
            }
            case "parent-target" -> {
                requireFields(object, PARENT_TARGET_FIELDS, "parent target");
                yield new ParentTarget(
                        ExecutionScopeCodec.parseRegionKey(object.get("region")),
                        requiredString(object, "parent_id"));
            }
            case "slide-target" -> {
                requireFields(object, SLIDE_TARGET_FIELDS, "slide target");
                yield new SlideTarget();
            }
            default ->
                    throw new IllegalArgumentException("unsupported target selector type: " + type);
        };
    }

    /** Parse one nonempty canonical target-selection set. */
    public static TargetSelection parseTargetSelection(JsonElement value) {
        JsonObject object = requireExactObject(value, TARGET_SELECTION_FIELDS, "target selection");
        JsonArray array = requiredNonemptyArray(object.get("selectors"), "selectors");
        List<TargetSelector> selectors = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            selectors.add(parseTargetSelector(item));
        }
        return new TargetSelection(selectors);
    }

    /** Parse one all-model or selected-model work request. */
    public static ModelWorkSelection parseModelWorkSelection(JsonElement value) {
        JsonObject object = requiredObject(value, "model work selection");
        String type = requiredString(object, "type");
        return switch (type) {
            case "all-models" -> {
                requireFields(object, ALL_MODELS_FIELDS, "all-models selection");
                yield new AllModels(parseRegionSelection(object.get("regions")));
            }
            case "selected-models" -> {
                requireFields(object, SELECTED_MODELS_FIELDS, "selected-models selection");
                RegionSelection regions = parseRegionSelection(object.get("regions"));
                JsonArray array = requiredNonemptyArray(object.get("steps"), "steps");
                List<StepTargets> steps = new ArrayList<>(array.size());
                for (JsonElement item : array) {
                    JsonObject step = requireExactObject(item, STEP_TARGETS_FIELDS, "step targets");
                    steps.add(
                            new StepTargets(
                                    requiredString(step, "step_id"),
                                    parseTargetSelection(step.get("targets"))));
                }
                yield new SelectedModels(regions, steps);
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported model work selection type: " + type);
        };
    }

    /** Parse one explicit Fill, Redo, or Metrics user action. */
    public static ImageAction parseImageAction(JsonElement value) {
        JsonObject object = requiredObject(value, "image action");
        String type = requiredString(object, "type");
        return switch (type) {
            case "fill" -> {
                requireFields(object, MODEL_ACTION_FIELDS, "fill action");
                yield new Fill(
                        parseModelWorkSelection(object.get("work")),
                        requiredBoolean(
                                object.get("remove_overlapping_same_class_duplicates"),
                                "remove_overlapping_same_class_duplicates"));
            }
            case "redo" -> {
                requireFields(object, MODEL_ACTION_FIELDS, "redo action");
                yield new Redo(
                        parseModelWorkSelection(object.get("work")),
                        requiredBoolean(
                                object.get("remove_overlapping_same_class_duplicates"),
                                "remove_overlapping_same_class_duplicates"));
            }
            case "metrics" -> {
                requireFields(object, METRICS_ACTION_FIELDS, "metrics action");
                yield new Metrics(parseRegionSelection(object.get("targets")));
            }
            default -> throw new IllegalArgumentException("unsupported image action type: " + type);
        };
    }

    /** Encode one explicit user action using exact contract fields and variant tags. */
    public static JsonObject toJson(ImageAction action) {
        JsonObject value = new JsonObject();
        if (action instanceof Fill fill) {
            value.addProperty("type", "fill");
            value.add("work", toJson(fill.work()));
            value.addProperty(
                    "remove_overlapping_same_class_duplicates",
                    fill.removeOverlappingSameClassDuplicates());
        } else if (action instanceof Redo redo) {
            value.addProperty("type", "redo");
            value.add("work", toJson(redo.work()));
            value.addProperty(
                    "remove_overlapping_same_class_duplicates",
                    redo.removeOverlappingSameClassDuplicates());
        } else if (action instanceof Metrics metrics) {
            value.addProperty("type", "metrics");
            value.add("targets", toJson(metrics.targets()));
        } else {
            throw new IllegalArgumentException("unsupported image action");
        }
        return value;
    }

    /** Return canonical bytes for one structurally validated action. */
    public static byte[] canonicalBytes(ImageAction action) {
        return CanonicalCodec.canonicalBytes(toJson(action));
    }

    private static JsonObject toJson(ModelWorkSelection work) {
        JsonObject value = new JsonObject();
        if (work instanceof AllModels all) {
            value.addProperty("type", "all-models");
            value.add("regions", toJson(all.regions()));
        } else if (work instanceof SelectedModels selected) {
            value.addProperty("type", "selected-models");
            value.add("regions", toJson(selected.regions()));
            JsonArray steps = new JsonArray();
            for (StepTargets step : selected.steps()) {
                JsonObject item = new JsonObject();
                item.addProperty("step_id", step.stepId());
                item.add("targets", toJson(step.targets()));
                steps.add(item);
            }
            value.add("steps", steps);
        } else {
            throw new IllegalArgumentException("unsupported model work selection");
        }
        return value;
    }

    private static JsonObject toJson(RegionSelection selection) {
        JsonObject value = new JsonObject();
        if (selection instanceof RoiRegions rois) {
            value.addProperty("type", "roi-regions");
            value.add("roi_ids", uuidArray(rois.roiIds()));
        } else if (selection instanceof SlideRegion slide) {
            value.addProperty("type", "slide-region");
            value.add("region", toJson(slide.region()));
        } else {
            throw new IllegalArgumentException("unsupported region selection");
        }
        return value;
    }

    private static JsonObject toJson(SlideRegionSelection selection) {
        JsonObject value = new JsonObject();
        if (selection instanceof FullSlide) {
            value.addProperty("type", "full-slide");
        } else if (selection instanceof AnnotationUnion union) {
            value.addProperty("type", "annotation-union");
            value.add("annotation_ids", uuidArray(union.annotationIds()));
        } else {
            throw new IllegalArgumentException("unsupported slide region selector");
        }
        return value;
    }

    private static JsonObject toJson(TargetSelection selection) {
        JsonArray selectors = new JsonArray();
        for (TargetSelector selector : selection.selectors()) {
            selectors.add(toJson(selector));
        }
        JsonObject value = new JsonObject();
        value.add("selectors", selectors);
        return value;
    }

    private static JsonObject toJson(TargetSelector selector) {
        JsonObject value = new JsonObject();
        if (selector instanceof RoiTarget roi) {
            value.addProperty("type", "roi-target");
            value.addProperty("roi_id", roi.roiId());
        } else if (selector instanceof ParentTarget parent) {
            value.addProperty("type", "parent-target");
            value.add("region", ExecutionScopeCodec.toJson(parent.region()));
            value.addProperty("parent_id", parent.parentId());
        } else if (selector instanceof SlideTarget) {
            value.addProperty("type", "slide-target");
        } else {
            throw new IllegalArgumentException("unsupported target selector");
        }
        return value;
    }

    private static byte[] canonicalSelectorBytes(TargetSelector selector) {
        return CanonicalCodec.canonicalBytes(toJson(selector));
    }

    private static void requireTargetsWithinRegions(
            TargetSelection targets, RegionSelection regions) {
        List<TargetSelector> selectors = targets.selectors();
        if (regions instanceof SlideRegion) {
            if (selectors.size() != 1 || !(selectors.get(0) instanceof SlideTarget)) {
                throw new IllegalArgumentException(
                        "slide-region selected steps require exactly one slide-target");
            }
            return;
        }

        Set<String> allowed = ((RoiRegions) regions).roiIds();
        for (TargetSelector selector : selectors) {
            final String roiId;
            if (selector instanceof SlideTarget) {
                throw new IllegalArgumentException(
                        "ROI-region selected steps cannot contain slide-target");
            } else if (selector instanceof RoiTarget roi) {
                roiId = roi.roiId();
            } else {
                ParentTarget parent = (ParentTarget) selector;
                if (!(parent.region() instanceof ExecutionScopeCodec.Roi roi)) {
                    throw new IllegalArgumentException(
                            "ROI-region parent-target must name an ROI region");
                }
                roiId = roi.roiId();
            }
            if (!allowed.contains(roiId)) {
                throw new IllegalArgumentException(
                        "step target lies outside the action-level region selection");
            }
        }
    }

    private static Set<String> parseUuidSet(JsonElement value, String path) {
        JsonArray array = requiredNonemptyArray(value, path);
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement item : array) {
            String uuid = requiredString(item, path);
            if (!result.add(uuid)) {
                throw new IllegalArgumentException(path + " must not contain duplicates");
            }
        }
        return result;
    }

    private static Set<String> canonicalUuidSet(Collection<String> values, String path) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(path + " must be a nonempty array");
        }
        List<String> sorted = new ArrayList<>(values.size());
        for (String value : values) {
            sorted.add(CanonicalCodec.requireCanonicalUuid(value, path));
        }
        sorted.sort(String::compareTo);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static JsonArray uuidArray(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
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

    private static JsonArray requiredNonemptyArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(path + " must be a nonempty array");
        }
        return value.getAsJsonArray();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean requiredBoolean(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}
