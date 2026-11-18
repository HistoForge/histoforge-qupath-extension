package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;

/** Exact run-owned training and validation assignment contracts. */
public final class TrainingSplitCodec {

    private static final Set<String> NONE_FIELDS = Set.of("type");
    private static final Set<String> SPLIT_FIELDS =
            Set.of("type", "unit", "assignment_source", "units");
    private static final Set<String> GENERATED_SPLIT_FIELDS =
            Set.of("type", "unit", "assignment_source", "units", "requested_validation_percent");
    private static final Set<String> ASSIGNMENT_FIELDS = Set.of("unit_index", "partition");
    private static final Set<String> SIDECAR_FIELDS =
            Set.of("schema_version", "dataset_digest", "split_plan");
    private static final Set<String> UNITS = Set.of("roi", "image", "project_group");
    private static final Set<String> SOURCES = Set.of("generated", "custom");
    private static final Set<String> PARTITIONS = Set.of("training", "validation");

    private TrainingSplitCodec() {}

    public sealed interface TrainingSplitPlan permits NoValidationPlan, ValidationSplitPlan {}

    public record NoValidationPlan() implements TrainingSplitPlan {}

    public record SplitUnitAssignment(int unitIndex, String partition) {}

    public record ValidationSplitPlan(
            String unit,
            String assignmentSource,
            OptionalInt requestedValidationPercent,
            List<SplitUnitAssignment> units)
            implements TrainingSplitPlan {
        public ValidationSplitPlan {
            requestedValidationPercent =
                    Objects.requireNonNull(
                            requestedValidationPercent, "requested_validation_percent");
            units = List.copyOf(units);
        }
    }

    public record TrainingSplitSidecar(String datasetDigest, TrainingSplitPlan splitPlan) {}

    public static TrainingSplitPlan parsePlan(
            JsonElement value, TrainingDatasetManifest manifest, TrainingApiLimits limits) {
        Objects.requireNonNull(manifest, "training dataset manifest");
        Objects.requireNonNull(limits, "training API limits");
        TrainingDatasetManifest dataset =
                TrainingDatasetCodec.parseManifest(
                        TrainingDatasetCodec.toJson(manifest, limits), limits);
        TrainingSplitPlan plan = parsePlanSyntax(value);
        if (plan instanceof NoValidationPlan) {
            return plan;
        }
        ValidationSplitPlan split = (ValidationSplitPlan) plan;
        int expectedCount = unitCount(split.unit(), dataset);
        if (split.units().size() != expectedCount) {
            throw new IllegalArgumentException(
                    "split units must assign every unit once in index order");
        }
        if ("project_group".equals(split.unit())
                && dataset.images().stream()
                        .anyMatch(image -> image.projectGroupIndex().isEmpty())) {
            throw new IllegalArgumentException(
                    "project-group splitting requires every image to have a group");
        }
        return split;
    }

    /** Parse a persisted assignment without requiring its retained dataset. */
    public static TrainingSplitPlan parsePlanSyntax(JsonElement value) {
        JsonObject object = TrainingJson.object(value, "training split plan");
        String type = TrainingJson.string(object, "type");
        if ("none".equals(type)) {
            TrainingJson.requireFields(object, NONE_FIELDS, "no-validation plan");
            return new NoValidationPlan();
        }
        if (!"split".equals(type)) {
            throw new IllegalArgumentException("unsupported split plan type: " + type);
        }

        String source = TrainingJson.string(object, "assignment_source");
        TrainingJson.requireFields(
                object,
                "generated".equals(source) ? GENERATED_SPLIT_FIELDS : SPLIT_FIELDS,
                "validation split plan");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("unsupported assignment_source");
        }
        String unit = TrainingJson.enumValue(object.get("unit"), UNITS, "unit");
        List<SplitUnitAssignment> assignments = new ArrayList<>();
        for (JsonElement itemValue : TrainingJson.array(object.get("units"), "units")) {
            JsonObject item =
                    TrainingJson.exactObject(itemValue, ASSIGNMENT_FIELDS, "split unit assignment");
            int index =
                    CanonicalCodec.requireNonnegativeInt32(item.get("unit_index"), "unit_index");
            assignments.add(
                    new SplitUnitAssignment(
                            index,
                            TrainingJson.enumValue(
                                    item.get("partition"), PARTITIONS, "partition")));
        }
        for (int index = 0; index < assignments.size(); index++) {
            if (assignments.get(index).unitIndex() != index) {
                throw new IllegalArgumentException(
                        "split units must assign every unit once in index order");
            }
        }
        Set<String> assignedPartitions = new HashSet<>();
        assignments.forEach(item -> assignedPartitions.add(item.partition()));
        if (!assignedPartitions.equals(PARTITIONS)) {
            throw new IllegalArgumentException(
                    "validation split requires nonempty training and validation");
        }
        OptionalInt requested = OptionalInt.empty();
        if ("generated".equals(source)) {
            int percent =
                    CanonicalCodec.requireInt32(
                            object.get("requested_validation_percent"),
                            "requested_validation_percent");
            if (percent < 1 || percent > 99) {
                throw new IllegalArgumentException(
                        "requested_validation_percent must be from 1 through 99");
            }
            requested = OptionalInt.of(percent);
        }
        return new ValidationSplitPlan(unit, source, requested, assignments);
    }

    public static TrainingSplitSidecar parseSidecar(
            JsonElement value, TrainingDatasetManifest manifest, TrainingApiLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(value, SIDECAR_FIELDS, "training split sidecar");
        if (CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version") != 1) {
            throw new IllegalArgumentException("unsupported training split sidecar schema_version");
        }
        TrainingDatasetManifest dataset =
                TrainingDatasetCodec.parseManifest(
                        TrainingDatasetCodec.toJson(manifest, limits), limits);
        String digest =
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "dataset_digest"), "dataset_digest");
        if (!digest.equals(dataset.datasetDigest())) {
            throw new IllegalArgumentException(
                    "split sidecar dataset_digest does not match manifest");
        }
        return new TrainingSplitSidecar(
                digest, parsePlan(object.get("split_plan"), dataset, limits));
    }

    public static JsonObject toJson(
            TrainingSplitPlan value, TrainingDatasetManifest manifest, TrainingApiLimits limits) {
        return encodePlan(parsePlan(encodePlan(value), manifest, limits));
    }

    public static JsonObject toJson(TrainingSplitPlan value) {
        return encodePlan(parsePlanSyntax(encodePlan(value)));
    }

    public static JsonObject toJson(
            TrainingSplitSidecar value,
            TrainingDatasetManifest manifest,
            TrainingApiLimits limits) {
        TrainingSplitSidecar checked = parseSidecar(encodeSidecar(value), manifest, limits);
        return encodeSidecar(checked);
    }

    private static int unitCount(String unit, TrainingDatasetManifest manifest) {
        return switch (unit) {
            case "roi" -> manifest.rois().size();
            case "image" -> manifest.images().size();
            case "project_group" -> manifest.projectGroups().size();
            default -> throw new IllegalArgumentException("unsupported split unit");
        };
    }

    private static JsonObject encodePlan(TrainingSplitPlan value) {
        Objects.requireNonNull(value, "training split plan");
        JsonObject result = new JsonObject();
        if (value instanceof NoValidationPlan) {
            result.addProperty("type", "none");
            return result;
        }
        if (!(value instanceof ValidationSplitPlan split)) {
            throw new IllegalArgumentException("unsupported training split plan");
        }
        result.addProperty("type", "split");
        result.addProperty("unit", split.unit());
        result.addProperty("assignment_source", split.assignmentSource());
        JsonArray units = new JsonArray();
        split.units().forEach(item -> units.add(encodeAssignment(item)));
        result.add("units", units);
        if (split.requestedValidationPercent().isPresent()) {
            result.addProperty(
                    "requested_validation_percent", split.requestedValidationPercent().getAsInt());
        }
        return result;
    }

    private static JsonObject encodeAssignment(SplitUnitAssignment value) {
        JsonObject result = new JsonObject();
        result.addProperty("unit_index", value.unitIndex());
        result.addProperty("partition", value.partition());
        return result;
    }

    private static JsonObject encodeSidecar(TrainingSplitSidecar value) {
        Objects.requireNonNull(value, "training split sidecar");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("dataset_digest", value.datasetDigest());
        result.add("split_plan", encodePlan(value.splitPlan()));
        return result;
    }
}
