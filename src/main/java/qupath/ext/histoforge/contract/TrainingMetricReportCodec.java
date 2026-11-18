package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportTypes.BestValidationSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.FinalWeightsSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricPolicy;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricSeries;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricValue;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingEpochMetricRecord;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingModelSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.UnavailableTrainerMetric;

/** Metric catalog, observation ordering, availability, and weight-selection semantics. */
final class TrainingMetricReportCodec {

    private static final Set<String> SERIES_FIELDS =
            Set.of("series_id", "display_name", "partition", "value_domain", "definition");
    private static final Set<String> POLICY_FIELDS =
            Set.of("series", "primary_validation_series_id", "primary_selection_direction");
    private static final Set<String> EPOCH_FIELDS = Set.of("epoch", "elapsed_seconds", "metrics");
    private static final Set<String> METRIC_FIELDS = Set.of("series_id", "value");
    private static final Set<String> UNAVAILABLE_FIELDS = Set.of("series_id", "reason");
    private static final Set<String> BEST_SELECTION_FIELDS =
            Set.of("type", "series_id", "value", "epoch", "selected_weight_digest");
    private static final Set<String> FINAL_SELECTION_FIELDS =
            Set.of("type", "reason", "epoch", "selected_weight_digest");

    private TrainingMetricReportCodec() {}

    static TrainerMetricPolicy parsePolicy(JsonElement value, TrainingReportLimits limits) {
        JsonObject object = TrainingJson.exactObject(value, POLICY_FIELDS, "metric policy");
        JsonArray values = TrainingJson.nonemptyArray(object.get("series"), "metric series");
        if (values.size() > limits.maxMetricSeries()) {
            throw new IllegalArgumentException("metric series exceed the training report limit");
        }
        List<TrainerMetricSeries> series = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement item : values) {
            JsonObject entry = TrainingJson.exactObject(item, SERIES_FIELDS, "metric series");
            String id = TrainingJson.stableId(entry.get("series_id"), "series_id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("metric series IDs must be unique");
            }
            series.add(
                    new TrainerMetricSeries(
                            id,
                            TrainingJson.text(
                                    entry.get("display_name"),
                                    "display_name",
                                    limits.maxTextBytes()),
                            TrainingJson.enumValue(
                                    entry.get("partition"),
                                    Set.of("training", "validation"),
                                    "partition"),
                            TrainingJson.enumValue(
                                    entry.get("value_domain"),
                                    Set.of("unit_interval", "real"),
                                    "value_domain"),
                            TrainingJson.text(
                                    entry.get("definition"), "definition", limits.maxTextBytes())));
        }
        String primary =
                TrainingJson.stableId(
                        object.get("primary_validation_series_id"), "primary_validation_series_id");
        if (series.stream()
                        .filter(item -> item.seriesId().equals(primary))
                        .filter(item -> item.partition().equals("validation"))
                        .count()
                != 1) {
            throw new IllegalArgumentException("primary metric must name one validation series");
        }
        return new TrainerMetricPolicy(
                series,
                primary,
                TrainingJson.enumValue(
                        object.get("primary_selection_direction"),
                        Set.of("minimize", "maximize"),
                        "primary_selection_direction"));
    }

    static List<TrainingEpochMetricRecord> parseEpochs(
            JsonElement value, TrainerMetricPolicy policy, int totalEpochs) {
        JsonArray values = TrainingJson.array(value, "epochs");
        List<String> order = policy.series().stream().map(TrainerMetricSeries::seriesId).toList();
        var seriesById = new HashMap<String, TrainerMetricSeries>();
        policy.series().forEach(series -> seriesById.put(series.seriesId(), series));
        List<TrainingEpochMetricRecord> result = new ArrayList<>();
        int previousEpoch = 0;
        long previousElapsed = 0;
        for (JsonElement item : values) {
            JsonObject object = TrainingJson.exactObject(item, EPOCH_FIELDS, "epoch record");
            int epoch = CanonicalCodec.requirePositiveInt32(object.get("epoch"), "epoch");
            long elapsed =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            object.get("elapsed_seconds"), "elapsed_seconds");
            if (epoch <= previousEpoch || epoch > totalEpochs) {
                throw new IllegalArgumentException(
                        "report epochs must be increasing and within total_epochs");
            }
            if (elapsed < previousElapsed) {
                throw new IllegalArgumentException("epoch elapsed_seconds must be nondecreasing");
            }
            List<TrainerMetricValue> metrics = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (JsonElement metricValue : TrainingJson.array(object.get("metrics"), "metrics")) {
                JsonObject metric =
                        TrainingJson.exactObject(metricValue, METRIC_FIELDS, "metric value");
                String id = TrainingJson.stableId(metric.get("series_id"), "series_id");
                ids.add(id);
                metrics.add(
                        new TrainerMetricValue(
                                id,
                                CanonicalCodec.requireFiniteDouble(metric.get("value"), "value")));
            }
            Set<String> idSet = new HashSet<>(ids);
            if (ids.size() != idSet.size() || !order.containsAll(ids)) {
                throw new IllegalArgumentException(
                        "epoch metrics must uniquely reference the metric policy");
            }
            if (!ids.equals(order.stream().filter(idSet::contains).toList())) {
                throw new IllegalArgumentException("epoch metrics must follow metric policy order");
            }
            if (metrics.stream()
                    .anyMatch(
                            metric ->
                                    "unit_interval"
                                                    .equals(
                                                            seriesById
                                                                    .get(metric.seriesId())
                                                                    .valueDomain())
                                            && (metric.value() < 0 || metric.value() > 1))) {
                throw new IllegalArgumentException(
                        "unit-interval metric values must be from 0 through 1");
            }
            result.add(new TrainingEpochMetricRecord(epoch, elapsed, metrics));
            previousEpoch = epoch;
            previousElapsed = elapsed;
        }
        return List.copyOf(result);
    }

    static List<UnavailableTrainerMetric> parseUnavailable(
            JsonElement value, TrainerMetricPolicy policy, TrainingReportLimits limits) {
        JsonArray values = TrainingJson.array(value, "unavailable_metrics");
        if (values.size() > limits.maxMetricSeries()) {
            throw new IllegalArgumentException("unavailable metrics exceed the report limit");
        }
        List<String> order = policy.series().stream().map(TrainerMetricSeries::seriesId).toList();
        List<UnavailableTrainerMetric> result = new ArrayList<>();
        for (JsonElement item : values) {
            JsonObject object =
                    TrainingJson.exactObject(item, UNAVAILABLE_FIELDS, "unavailable metric");
            result.add(
                    new UnavailableTrainerMetric(
                            TrainingJson.stableId(object.get("series_id"), "series_id"),
                            TrainingJson.text(
                                    object.get("reason"), "reason", limits.maxTextBytes())));
        }
        List<String> ids = result.stream().map(UnavailableTrainerMetric::seriesId).toList();
        Set<String> idSet = new HashSet<>(ids);
        if (ids.size() != idSet.size()
                || !ids.equals(order.stream().filter(idSet::contains).toList())) {
            throw new IllegalArgumentException(
                    "unavailable metrics must be unique and follow policy order");
        }
        return List.copyOf(result);
    }

    static TrainingModelSelection parseSelection(
            JsonElement value,
            TrainerMetricPolicy policy,
            List<TrainingEpochMetricRecord> epochs,
            boolean validationEnabled,
            int totalEpochs) {
        JsonObject object = TrainingJson.object(value, "model selection");
        String type = TrainingJson.string(object, "type");
        List<EpochValue> observations = primaryObservations(policy, epochs);
        if ("best-validation".equals(type)) {
            TrainingJson.requireFields(object, BEST_SELECTION_FIELDS, "best validation selection");
            if (!validationEnabled
                    || !TrainingJson.string(object, "series_id")
                            .equals(policy.primaryValidationSeriesId())
                    || observations.isEmpty()) {
                throw new IllegalArgumentException(
                        "best-validation selection has no eligible primary value");
            }
            double best =
                    "minimize".equals(policy.primarySelectionDirection())
                            ? observations.stream()
                                    .mapToDouble(EpochValue::value)
                                    .min()
                                    .orElseThrow()
                            : observations.stream()
                                    .mapToDouble(EpochValue::value)
                                    .max()
                                    .orElseThrow();
            int bestEpoch =
                    observations.stream()
                            .filter(item -> item.value() == best)
                            .findFirst()
                            .orElseThrow()
                            .epoch();
            double encoded =
                    CanonicalCodec.requireFiniteDouble(object.get("value"), "selection value");
            int epoch = CanonicalCodec.requirePositiveInt32(object.get("epoch"), "selection epoch");
            if (encoded != best || epoch != bestEpoch) {
                throw new IllegalArgumentException(
                        "best-validation selection does not match report observations");
            }
            return new BestValidationSelection(
                    policy.primaryValidationSeriesId(),
                    encoded,
                    epoch,
                    CanonicalCodec.requireSha256(
                            TrainingJson.string(object, "selected_weight_digest"),
                            "selected_weight_digest"));
        }
        if ("final-weights".equals(type)) {
            TrainingJson.requireFields(object, FINAL_SELECTION_FIELDS, "final weights selection");
            String reason =
                    TrainingJson.enumValue(
                            object.get("reason"),
                            Set.of("no_validation", "primary_metric_unavailable"),
                            "reason");
            String expectedReason =
                    validationEnabled ? "primary_metric_unavailable" : "no_validation";
            if (!reason.equals(expectedReason) || (validationEnabled && !observations.isEmpty())) {
                throw new IllegalArgumentException(
                        "final-weights reason does not match validation observations");
            }
            int epoch = CanonicalCodec.requirePositiveInt32(object.get("epoch"), "epoch");
            if (epoch != totalEpochs) {
                throw new IllegalArgumentException("final weights must name the final epoch");
            }
            return new FinalWeightsSelection(
                    reason,
                    epoch,
                    CanonicalCodec.requireSha256(
                            TrainingJson.string(object, "selected_weight_digest"),
                            "selected_weight_digest"));
        }
        throw new IllegalArgumentException("unsupported training model selection type");
    }

    static JsonObject encodePolicy(TrainerMetricPolicy policy) {
        JsonObject value = new JsonObject();
        JsonArray series = new JsonArray();
        policy.series().forEach(item -> series.add(encodeSeries(item)));
        value.add("series", series);
        value.addProperty("primary_validation_series_id", policy.primaryValidationSeriesId());
        value.addProperty("primary_selection_direction", policy.primarySelectionDirection());
        return value;
    }

    static JsonObject encodeEpoch(TrainingEpochMetricRecord epoch) {
        JsonObject value = new JsonObject();
        value.addProperty("epoch", epoch.epoch());
        value.addProperty("elapsed_seconds", epoch.elapsedSeconds());
        JsonArray metrics = new JsonArray();
        epoch.metrics().forEach(item -> metrics.add(encodeMetric(item)));
        value.add("metrics", metrics);
        return value;
    }

    static JsonObject encodeUnavailable(UnavailableTrainerMetric unavailable) {
        JsonObject value = new JsonObject();
        value.addProperty("series_id", unavailable.seriesId());
        value.addProperty("reason", unavailable.reason());
        return value;
    }

    static JsonObject encodeSelection(TrainingModelSelection selection) {
        JsonObject value = new JsonObject();
        if (selection instanceof BestValidationSelection best) {
            value.addProperty("type", "best-validation");
            value.addProperty("series_id", best.seriesId());
            value.addProperty("value", best.value());
            value.addProperty("epoch", best.epoch());
            value.addProperty("selected_weight_digest", best.selectedWeightDigest());
            return value;
        }
        if (selection instanceof FinalWeightsSelection finalWeights) {
            value.addProperty("type", "final-weights");
            value.addProperty("reason", finalWeights.reason());
            value.addProperty("epoch", finalWeights.epoch());
            value.addProperty("selected_weight_digest", finalWeights.selectedWeightDigest());
            return value;
        }
        throw new IllegalArgumentException("unsupported training model selection");
    }

    private static JsonObject encodeSeries(TrainerMetricSeries series) {
        JsonObject value = new JsonObject();
        value.addProperty("series_id", series.seriesId());
        value.addProperty("display_name", series.displayName());
        value.addProperty("partition", series.partition());
        value.addProperty("value_domain", series.valueDomain());
        value.addProperty("definition", series.definition());
        return value;
    }

    private static JsonObject encodeMetric(TrainerMetricValue metric) {
        JsonObject value = new JsonObject();
        value.addProperty("series_id", metric.seriesId());
        value.addProperty("value", metric.value());
        return value;
    }

    private static List<EpochValue> primaryObservations(
            TrainerMetricPolicy policy, List<TrainingEpochMetricRecord> epochs) {
        List<EpochValue> result = new ArrayList<>();
        for (TrainingEpochMetricRecord epoch : epochs) {
            epoch.metrics().stream()
                    .filter(item -> item.seriesId().equals(policy.primaryValidationSeriesId()))
                    .findFirst()
                    .ifPresent(item -> result.add(new EpochValue(epoch.epoch(), item.value())));
        }
        return result;
    }

    private record EpochValue(int epoch, double value) {}
}
