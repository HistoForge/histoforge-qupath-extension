package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunTypes.PrimaryMetricObservation;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingProgress;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunPage;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunSummary;

/** Closed ordered and byte-bounded Jobs-page codec for managed training runs. */
public final class TrainingRunPageCodec {

    private static final Set<String> SUMMARY_REQUIRED_FIELDS =
            Set.of(
                    "run_id",
                    "result_display_name",
                    "source_module_ref",
                    "selected_model_id",
                    "result_form",
                    "state",
                    "progress",
                    "report_revision",
                    "created_at",
                    "updated_at");
    private static final Set<String> SUMMARY_OPTIONAL_FIELDS = Set.of("primary_metric", "result");
    private static final Set<String> PRIMARY_METRIC_FIELDS =
            Set.of("series_id", "display_name", "value", "epoch");
    private static final Set<String> PAGE_REQUIRED_FIELDS = Set.of("items");
    private static final Set<String> PAGE_OPTIONAL_FIELDS = Set.of("next_cursor");
    private static final Set<String> STATES =
            Set.of("QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> RESULT_FORMS =
            Set.of("selected_model_only", "complete_source_workflow");

    private TrainingRunPageCodec() {}

    /** Parse one bounded Jobs-row summary. */
    public static TrainingRunSummary parseSummary(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                objectWithOptional(
                        value,
                        SUMMARY_REQUIRED_FIELDS,
                        SUMMARY_OPTIONAL_FIELDS,
                        "training run summary");
        String state = TrainingJson.enumValue(object.get("state"), STATES, "state");
        TrainingProgress progress = TrainingRunCodec.parseProgress(object.get("progress"), limits);
        if (Set.of("FINALIZING", "SUCCEEDED").contains(state)
                && progress.completedEpochs() != progress.totalEpochs()) {
            throw new IllegalArgumentException(
                    state + " requires every training epoch to be complete");
        }
        Optional<TrainingRunResult> result =
                object.has("result")
                        ? Optional.of(
                                TrainingRunCodec.parseResult(object.get("result"), state, limits))
                        : Optional.empty();
        TrainingRunCodec.requireTerminalResultPresence(state, result);
        Optional<PrimaryMetricObservation> metric =
                object.has("primary_metric")
                        ? Optional.of(parsePrimaryMetric(object.get("primary_metric"), limits))
                        : Optional.empty();
        if (metric.filter(item -> item.epoch() > progress.completedEpochs()).isPresent()) {
            throw new IllegalArgumentException(
                    "primary metric epoch exceeds completed training progress");
        }
        Instant createdAt = TrainingRunCodec.parseTimestamp(object, "created_at");
        Instant updatedAt = TrainingRunCodec.parseTimestamp(object, "updated_at");
        TrainingRunCodec.requireOrderedTimes(createdAt, updatedAt);
        ModuleRef sourceModule =
                ModuleRef.parse(TrainingJson.string(object, "source_module_ref"))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "source_module_ref must be canonical owner/name"));
        return new TrainingRunSummary(
                CanonicalCodec.requireCanonicalUuid(
                        TrainingJson.string(object, "run_id"), "run_id"),
                TrainingJson.text(
                        object.get("result_display_name"),
                        "result_display_name",
                        limits.report().maxTextBytes()),
                sourceModule,
                TrainingJson.modelId(object.get("selected_model_id"), "selected_model_id"),
                TrainingJson.enumValue(object.get("result_form"), RESULT_FORMS, "result_form"),
                state,
                progress,
                CanonicalCodec.requirePositiveInt32(
                        object.get("report_revision"), "report_revision"),
                metric,
                result,
                createdAt,
                updatedAt);
    }

    /** Parse one ordered, count-bounded, and byte-bounded page of Jobs rows. */
    public static TrainingRunPage parsePage(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                objectWithOptional(
                        value, PAGE_REQUIRED_FIELDS, PAGE_OPTIONAL_FIELDS, "training run page");
        JsonArray array = TrainingJson.array(object.get("items"), "items");
        if (array.size() > limits.maxPageSize()) {
            throw new IllegalArgumentException("training run page exceeds max_page_size");
        }
        List<TrainingRunSummary> items = new ArrayList<>();
        for (JsonElement item : array) {
            items.add(parseSummary(item, limits));
        }
        requirePageOrder(items);
        Optional<String> cursor =
                object.has("next_cursor")
                        ? Optional.of(
                                CanonicalCodec.requireNonblank(
                                        TrainingJson.string(object, "next_cursor"), "next_cursor"))
                        : Optional.empty();
        if (items.isEmpty() && cursor.isPresent()) {
            throw new IllegalArgumentException("empty training run page cannot have next_cursor");
        }
        TrainingRunPage page = new TrainingRunPage(items, cursor);
        if (CanonicalCodec.canonicalBytes(encodePage(page)).length > limits.maxPageEncodedBytes()) {
            throw new IllegalArgumentException("training run page exceeds max_page_encoded_bytes");
        }
        return page;
    }

    public static JsonObject toJson(TrainingRunSummary value, TrainingApiLimits limits) {
        return encodeSummary(parseSummary(encodeSummary(value), limits));
    }

    public static JsonObject toJson(TrainingRunPage value, TrainingApiLimits limits) {
        return encodePage(parsePage(encodePage(value), limits));
    }

    private static PrimaryMetricObservation parsePrimaryMetric(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(
                        value, PRIMARY_METRIC_FIELDS, "primary metric observation");
        return new PrimaryMetricObservation(
                TrainingJson.stableId(object.get("series_id"), "primary_metric.series_id"),
                TrainingJson.text(
                        object.get("display_name"),
                        "primary_metric.display_name",
                        limits.report().maxTextBytes()),
                CanonicalCodec.requireFiniteDouble(object.get("value"), "primary_metric.value"),
                CanonicalCodec.requirePositiveInt32(object.get("epoch"), "primary_metric.epoch"));
    }

    private static void requirePageOrder(List<TrainingRunSummary> items) {
        Set<String> keys = new HashSet<>();
        Set<String> runIds = new HashSet<>();
        TrainingRunSummary previous = null;
        for (TrainingRunSummary item : items) {
            String key =
                    CanonicalCodec.rfc3339Millis(item.createdAt(), "created_at") + item.runId();
            if (!keys.add(key)
                    || !runIds.add(item.runId())
                    || (previous != null
                            && (item.createdAt().isAfter(previous.createdAt())
                                    || (item.createdAt().equals(previous.createdAt())
                                            && item.runId().compareTo(previous.runId()) < 0)))) {
                throw new IllegalArgumentException("training run page is not uniquely ordered");
            }
            previous = item;
        }
    }

    private static JsonObject encodeSummary(TrainingRunSummary value) {
        Objects.requireNonNull(value, "training run summary");
        JsonObject result = new JsonObject();
        result.addProperty("run_id", value.runId());
        result.addProperty("result_display_name", value.resultDisplayName());
        result.addProperty("source_module_ref", value.sourceModuleRef().toString());
        result.addProperty("selected_model_id", value.selectedModelId());
        result.addProperty("result_form", value.resultForm());
        result.addProperty("state", value.state());
        result.add("progress", TrainingRunCodec.encodeProgress(value.progress()));
        result.addProperty("report_revision", value.reportRevision());
        value.primaryMetric()
                .ifPresent(item -> result.add("primary_metric", encodePrimaryMetric(item)));
        value.result().ifPresent(item -> result.add("result", TrainingRunCodec.encodeResult(item)));
        result.addProperty(
                "created_at", CanonicalCodec.rfc3339Millis(value.createdAt(), "created_at"));
        result.addProperty(
                "updated_at", CanonicalCodec.rfc3339Millis(value.updatedAt(), "updated_at"));
        return result;
    }

    private static JsonObject encodePage(TrainingRunPage value) {
        Objects.requireNonNull(value, "training run page");
        JsonObject result = new JsonObject();
        JsonArray items = new JsonArray();
        value.items().forEach(item -> items.add(encodeSummary(item)));
        result.add("items", items);
        value.nextCursor().ifPresent(item -> result.addProperty("next_cursor", item));
        return result;
    }

    private static JsonObject encodePrimaryMetric(PrimaryMetricObservation value) {
        JsonObject result = new JsonObject();
        result.addProperty("series_id", value.seriesId());
        result.addProperty("display_name", value.displayName());
        result.addProperty("value", value.value());
        result.addProperty("epoch", value.epoch());
        return result;
    }

    private static JsonObject objectWithOptional(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        JsonObject object = TrainingJson.object(value, path);
        if (!object.keySet().containsAll(required)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }
}
