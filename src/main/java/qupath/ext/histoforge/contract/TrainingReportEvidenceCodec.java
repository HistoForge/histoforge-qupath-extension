package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportTypes.AugmentationPresentation;
import qupath.ext.histoforge.contract.TrainingReportTypes.EnabledAugmentationOperator;
import qupath.ext.histoforge.contract.TrainingReportTypes.ResolvedTrainerComponent;
import qupath.ext.histoforge.contract.TrainingReportTypes.ResolvedTrainerConfiguration;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingPartitionCounts;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReportMessage;

/** Bounded resolved trainer configuration, partition counts, and report messages. */
final class TrainingReportEvidenceCodec {

    private static final Set<String> COMPONENT_FIELDS = Set.of("profile_id", "parameters");
    private static final Set<String> CONFIGURATION_FIELDS =
            Set.of(
                    "optimizer",
                    "scheduler",
                    "sampler",
                    "objective",
                    "augmentation",
                    "preprocessing");
    private static final Set<String> COUNTS_FIELDS =
            Set.of("image_count", "roi_count", "negative_roi_count", "target_object_counts");
    private static final Set<String> MESSAGE_FIELDS = Set.of("severity", "message");
    private static final Set<String> AUGMENTATION_PRESENTATION_FIELDS =
            Set.of(
                    "base_profile_id",
                    "base_profile_display_name",
                    "default_enabled_operator_ids",
                    "enabled_operators");
    private static final Set<String> ENABLED_OPERATOR_FIELDS =
            Set.of("operator_id", "display_name");

    private TrainingReportEvidenceCodec() {}

    static ResolvedTrainerConfiguration parseConfiguration(
            JsonElement value, TrainingReportLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(
                        value, CONFIGURATION_FIELDS, "resolved trainer configuration");
        return new ResolvedTrainerConfiguration(
                parseComponent(object.get("optimizer"), limits, "optimizer"),
                parseComponent(object.get("scheduler"), limits, "scheduler"),
                parseComponent(object.get("sampler"), limits, "sampler"),
                parseComponent(object.get("objective"), limits, "objective"),
                parseComponent(object.get("augmentation"), limits, "augmentation"),
                parseComponent(object.get("preprocessing"), limits, "preprocessing"));
    }

    static TrainingPartitionCounts parseCounts(JsonElement value, List<String> targetClasses) {
        JsonObject object = TrainingJson.exactObject(value, COUNTS_FIELDS, "partition counts");
        int imageCount =
                CanonicalCodec.requirePositiveInt32(object.get("image_count"), "image_count");
        int roiCount = CanonicalCodec.requirePositiveInt32(object.get("roi_count"), "roi_count");
        int negativeCount =
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("negative_roi_count"), "negative_roi_count");
        JsonObject values =
                TrainingJson.object(object.get("target_object_counts"), "target_object_counts");
        if (!values.keySet().equals(Set.copyOf(targetClasses))) {
            throw new IllegalArgumentException("target counts must match task target classes");
        }
        Map<String, Long> targetCounts = new LinkedHashMap<>();
        targetClasses.forEach(
                pathClass ->
                        targetCounts.put(
                                pathClass,
                                CanonicalCodec.requireNonnegativeSafeInteger(
                                        values.get(pathClass),
                                        "target_object_counts[" + pathClass + "]")));
        if (imageCount > roiCount || negativeCount > roiCount) {
            throw new IllegalArgumentException("training partition counts are inconsistent");
        }
        return new TrainingPartitionCounts(imageCount, roiCount, negativeCount, targetCounts);
    }

    static List<TrainingReportMessage> parseMessages(
            JsonElement value, TrainingReportLimits limits) {
        JsonArray values = TrainingJson.array(value, "messages");
        if (values.size() > limits.maxMessages()) {
            throw new IllegalArgumentException("training report messages exceed their limit");
        }
        List<TrainingReportMessage> result = new ArrayList<>();
        for (JsonElement item : values) {
            JsonObject object = TrainingJson.exactObject(item, MESSAGE_FIELDS, "report message");
            result.add(
                    new TrainingReportMessage(
                            TrainingJson.enumValue(
                                    object.get("severity"), Set.of("info", "warning"), "severity"),
                            TrainingJson.text(
                                    object.get("message"), "message", limits.maxTextBytes())));
        }
        return List.copyOf(result);
    }

    static AugmentationPresentation parseAugmentationPresentation(
            ResolvedTrainerComponent component,
            TrainingAugmentationSelection selection,
            TrainingReportLimits limits) {
        JsonObject parameters = component.parameters();
        if (!parameters.keySet().containsAll(AUGMENTATION_PRESENTATION_FIELDS)) {
            throw new IllegalArgumentException(
                    "resolved augmentation presentation has missing fields");
        }
        String baseProfileId =
                TrainingJson.stableId(parameters.get("base_profile_id"), "base_profile_id");
        if (!baseProfileId.equals(selection.profileId())) {
            throw new IllegalArgumentException(
                    "resolved augmentation base profile differs from selection");
        }
        String displayName =
                TrainingJson.text(
                        parameters.get("base_profile_display_name"),
                        "base_profile_display_name",
                        limits.maxTextBytes());
        List<String> defaults =
                stableIds(
                        parameters.get("default_enabled_operator_ids"),
                        "default_enabled_operator_ids");
        if (defaults.size() != new HashSet<>(defaults).size()) {
            throw new IllegalArgumentException(
                    "default enabled augmentation operators must be unique");
        }
        List<EnabledAugmentationOperator> enabled = new ArrayList<>();
        for (JsonElement value :
                TrainingJson.array(parameters.get("enabled_operators"), "enabled_operators")) {
            JsonObject item =
                    TrainingJson.exactObject(
                            value, ENABLED_OPERATOR_FIELDS, "enabled augmentation operator");
            enabled.add(
                    new EnabledAugmentationOperator(
                            TrainingJson.stableId(item.get("operator_id"), "operator_id"),
                            TrainingJson.text(
                                    item.get("display_name"),
                                    "display_name",
                                    limits.maxTextBytes())));
        }
        List<String> enabledIds =
                enabled.stream().map(EnabledAugmentationOperator::operatorId).toList();
        if (enabledIds.size() != new HashSet<>(enabledIds).size()) {
            throw new IllegalArgumentException("enabled augmentation operators must be unique");
        }
        if (!enabledIds.equals(selection.enabledOperatorIds())) {
            throw new IllegalArgumentException(
                    "resolved enabled augmentation operators differ from selection");
        }
        return new AugmentationPresentation(baseProfileId, displayName, defaults, enabled);
    }

    static void validateFailureText(GatewayErrorCodec.Error error, TrainingReportLimits limits) {
        TrainingJson.text(
                new com.google.gson.JsonPrimitive(error.message()),
                "failure.message",
                limits.maxTextBytes());
        error.resourceId()
                .ifPresent(
                        value ->
                                TrainingJson.text(
                                        new com.google.gson.JsonPrimitive(value),
                                        "failure.resource_id",
                                        limits.maxTextBytes()));
    }

    static JsonObject encodeConfiguration(ResolvedTrainerConfiguration configuration) {
        JsonObject value = new JsonObject();
        value.add("optimizer", encodeComponent(configuration.optimizer()));
        value.add("scheduler", encodeComponent(configuration.scheduler()));
        value.add("sampler", encodeComponent(configuration.sampler()));
        value.add("objective", encodeComponent(configuration.objective()));
        value.add("augmentation", encodeComponent(configuration.augmentation()));
        value.add("preprocessing", encodeComponent(configuration.preprocessing()));
        return value;
    }

    static JsonObject encodeCounts(TrainingPartitionCounts counts) {
        JsonObject value = new JsonObject();
        value.addProperty("image_count", counts.imageCount());
        value.addProperty("roi_count", counts.roiCount());
        value.addProperty("negative_roi_count", counts.negativeRoiCount());
        JsonObject targets = new JsonObject();
        counts.targetObjectCounts().forEach(targets::addProperty);
        value.add("target_object_counts", targets);
        return value;
    }

    static JsonObject encodeMessage(TrainingReportMessage message) {
        JsonObject value = new JsonObject();
        value.addProperty("severity", message.severity());
        value.addProperty("message", message.message());
        return value;
    }

    private static ResolvedTrainerComponent parseComponent(
            JsonElement value, TrainingReportLimits limits, String path) {
        JsonObject object = TrainingJson.exactObject(value, COMPONENT_FIELDS, path + " component");
        JsonObject parameters = TrainingJson.object(object.get("parameters"), path + ".parameters");
        JsonObject canonical = CanonicalCodec.canonicalTree(parameters).getAsJsonObject();
        if (CanonicalCodec.canonicalBytes(canonical).length
                > limits.maxComponentParametersBytes()) {
            throw new IllegalArgumentException(path + ".parameters exceed their byte limit");
        }
        Shape shape = shape(canonical);
        if (shape.depth() > limits.maxComponentParameterDepth()) {
            throw new IllegalArgumentException(path + ".parameters exceed their depth limit");
        }
        if (shape.entries() > limits.maxComponentCollectionEntries()) {
            throw new IllegalArgumentException(
                    path + ".parameters exceed their collection-entry limit");
        }
        return new ResolvedTrainerComponent(
                TrainingJson.stableId(object.get("profile_id"), "profile_id"), canonical);
    }

    private static List<String> stableIds(JsonElement value, String path) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : TrainingJson.array(value, path)) {
            result.add(TrainingJson.stableId(item, path + " item"));
        }
        return List.copyOf(result);
    }

    private static JsonObject encodeComponent(ResolvedTrainerComponent component) {
        JsonObject value = new JsonObject();
        value.addProperty("profile_id", component.profileId());
        value.add("parameters", component.parameters());
        return value;
    }

    private static Shape shape(JsonElement value) {
        if (value.isJsonObject()) {
            int depth = 0;
            long entries = value.getAsJsonObject().size();
            for (JsonElement child : value.getAsJsonObject().asMap().values()) {
                Shape nested = shape(child);
                depth = Math.max(depth, nested.depth());
                entries += nested.entries();
            }
            return new Shape(1 + depth, entries);
        }
        if (value.isJsonArray()) {
            int depth = 0;
            long entries = value.getAsJsonArray().size();
            for (JsonElement child : value.getAsJsonArray()) {
                Shape nested = shape(child);
                depth = Math.max(depth, nested.depth());
                entries += nested.entries();
            }
            return new Shape(1 + depth, entries);
        }
        return new Shape(0, 0);
    }

    private record Shape(int depth, long entries) {}
}
