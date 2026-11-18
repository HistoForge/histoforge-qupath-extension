package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportTypes.FinalWeightsSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingReportTypes.ResolvedTrainerConfiguration;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricPolicy;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricSeries;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingEpochMetricRecord;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingModelSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingPartitionCounts;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.contract.TrainingReportTypes.UnavailableTrainerMetric;
import qupath.ext.histoforge.contract.TrainingSplitCodec.NoValidationPlan;

/** Closed schema-1 managed-training report and module-report response codec. */
public final class TrainingReportCodec {

    private static final Set<String> RESPONSE_FIELDS =
            Set.of("module_identity", "report_digest", "report");
    private static final Set<String> REPORT_REQUIRED_FIELDS =
            Set.of(
                    "schema_version",
                    "run_id",
                    "state",
                    "dataset_digest",
                    "dataset_image_count",
                    "split_plan",
                    "source_module",
                    "selected_model_id",
                    "starting_weight_digest",
                    "class_initialization",
                    "source_model_output",
                    "source_model_microns_per_pixel",
                    "result_form",
                    "result_display_name",
                    "result_inference_gpu",
                    "task",
                    "trainer_build",
                    "training_seed",
                    "trainer_settings",
                    "total_epochs",
                    "field_profile",
                    "augmentation",
                    "trainer_configuration",
                    "training_counts",
                    "metric_policy",
                    "epochs",
                    "unavailable_metrics",
                    "messages");
    private static final Set<String> REPORT_OPTIONAL_FIELDS =
            Set.of("validation_counts", "model_selection", "failure", "min_instance_pixels");
    private static final Set<String> STATES =
            Set.of("QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private TrainingReportCodec() {}

    /** Parse a module-owned final report and require its echoed current module identity. */
    public static ModuleTrainingReportResponse parseModuleResponse(
            JsonElement value,
            TrainingReportLimits limits,
            ModuleContentIdentity expectedIdentity,
            String expectedReportDigest) {
        Objects.requireNonNull(expectedIdentity, "expected module identity");
        String expectedDigest =
                CanonicalCodec.requireSha256(expectedReportDigest, "expected report digest");
        JsonObject object = TrainingJson.exactObject(value, RESPONSE_FIELDS, "module report");
        ModuleContentIdentity identity =
                InferenceIdentityCodec.parseModuleContentIdentity(object.get("module_identity"));
        if (!identity.equals(expectedIdentity)) {
            throw new IllegalArgumentException(
                    "module report identity differs from the captured module descriptor");
        }
        TrainingReport report = parseReport(object.get("report"), limits);
        if (!"SUCCEEDED".equals(report.state())) {
            throw new IllegalArgumentException("module report must describe a successful run");
        }
        String digest =
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "report_digest"), "report_digest");
        if (!digest.equals(expectedDigest)) {
            throw new IllegalArgumentException(
                    "module report digest differs from the captured module descriptor");
        }
        if (!digest.equals(CanonicalCodec.canonicalDigest(encodeReport(report)))) {
            throw new IllegalArgumentException("module report digest does not match report");
        }
        return new ModuleTrainingReportResponse(identity, digest, report);
    }

    /** Parse one complete report revision and enforce its cross-field invariants. */
    public static TrainingReport parseReport(JsonElement value, TrainingReportLimits limits) {
        TrainingReportLimits bounded = Objects.requireNonNull(limits, "training report limits");
        JsonObject object =
                objectWithOptional(
                        value, REPORT_REQUIRED_FIELDS, REPORT_OPTIONAL_FIELDS, "training report");
        if (CanonicalCodec.canonicalBytes(object).length > bounded.maxReportBytes()) {
            throw new IllegalArgumentException("training report exceeds max_report_bytes");
        }
        int schema = CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version");
        if (schema != 1) {
            throw new IllegalArgumentException("unsupported training report schema_version");
        }
        String state = TrainingJson.enumValue(object.get("state"), STATES, "state");
        var splitPlan = TrainingSplitCodec.parsePlanSyntax(object.get("split_plan"));
        var task = TrainingTaskCodec.parseTask(object.get("task"));
        var initialization =
                TrainingClassSchema.Initialization.parse(
                        TrainingJson.string(object, "class_initialization"));
        var sourceOutput = ModelOutputCodec.parse(object.get("source_model_output"));
        TrainingClassSchema.requireTransition(sourceOutput, task.modelOutput(), initialization);
        var settings = TrainingCapabilityCodec.parseSettings(object.get("trainer_settings"));
        TrainingCapabilityCodec.requireSettingsApplicable(settings, task.outputType());
        int totalEpochs =
                CanonicalCodec.requirePositiveInt32(object.get("total_epochs"), "total_epochs");
        if (totalEpochs > bounded.maxEpochs()) {
            throw new IllegalArgumentException("total_epochs exceeds the training report limit");
        }
        var fieldProfile = TrainingTaskCodec.parseFieldProfile(object.get("field_profile"));
        var augmentation =
                TrainingCapabilityCodec.parseAugmentationSelectionSyntax(
                        object.get("augmentation"));
        ResolvedTrainerConfiguration configuration =
                TrainingReportEvidenceCodec.parseConfiguration(
                        object.get("trainer_configuration"), bounded);
        var augmentationPresentation =
                TrainingReportEvidenceCodec.parseAugmentationPresentation(
                        configuration.augmentation(), augmentation, bounded);
        String preprocessing =
                fieldProfile instanceof TrainingTaskCodec.FixedFieldProfile fixed
                        ? fixed.preprocessing()
                        : ((TrainingTaskCodec.VariableFieldProfile) fieldProfile).preprocessing();
        if (!configuration.preprocessing().profileId().equals(preprocessing)) {
            throw new IllegalArgumentException("resolved preprocessing differs from field_profile");
        }

        TrainerMetricPolicy policy =
                TrainingMetricReportCodec.parsePolicy(object.get("metric_policy"), bounded);
        Map<String, TrainerMetricSeries> seriesById = new HashMap<>();
        policy.series().forEach(series -> seriesById.put(series.seriesId(), series));
        TrainingPartitionCounts trainingCounts =
                TrainingReportEvidenceCodec.parseCounts(
                        object.get("training_counts"), task.targetClasses());
        boolean validationEnabled = !(splitPlan instanceof NoValidationPlan);
        Optional<TrainingPartitionCounts> validationCounts =
                object.has("validation_counts")
                        ? Optional.of(
                                TrainingReportEvidenceCodec.parseCounts(
                                        object.get("validation_counts"), task.targetClasses()))
                        : Optional.empty();
        if (validationEnabled != validationCounts.isPresent()) {
            throw new IllegalArgumentException("validation_counts presence must match split_plan");
        }

        List<TrainingEpochMetricRecord> epochs =
                TrainingMetricReportCodec.parseEpochs(object.get("epochs"), policy, totalEpochs);
        List<UnavailableTrainerMetric> unavailable =
                TrainingMetricReportCodec.parseUnavailable(
                        object.get("unavailable_metrics"), policy, bounded);
        Set<String> observedIds = new HashSet<>();
        epochs.forEach(
                epoch -> epoch.metrics().forEach(metric -> observedIds.add(metric.seriesId())));
        Set<String> unavailableIds = new HashSet<>();
        unavailable.forEach(metric -> unavailableIds.add(metric.seriesId()));
        if (!disjoint(observedIds, unavailableIds)) {
            throw new IllegalArgumentException("an observed metric cannot also be unavailable");
        }
        var messages = TrainingReportEvidenceCodec.parseMessages(object.get("messages"), bounded);
        Optional<TrainingModelSelection> selection =
                object.has("model_selection")
                        ? Optional.of(
                                TrainingMetricReportCodec.parseSelection(
                                        object.get("model_selection"),
                                        policy,
                                        epochs,
                                        validationEnabled,
                                        totalEpochs))
                        : Optional.empty();
        if ("SUCCEEDED".equals(state)
                && selection.filter(FinalWeightsSelection.class::isInstance).isPresent()
                && ((FinalWeightsSelection) selection.orElseThrow())
                        .reason()
                        .equals("primary_metric_unavailable")
                && !unavailableIds.contains(policy.primaryValidationSeriesId())) {
            throw new IllegalArgumentException("unavailable primary metric requires a reason");
        }
        Optional<GatewayErrorCodec.Error> failure =
                object.has("failure")
                        ? Optional.of(GatewayErrorCodec.parse(object.get("failure")))
                        : Optional.empty();
        failure.ifPresent(item -> TrainingReportEvidenceCodec.validateFailureText(item, bounded));
        validateState(state, totalEpochs, epochs, unavailable, selection, failure);
        validateMetricCoverage(
                state, validationEnabled, policy, seriesById, observedIds, unavailableIds);

        return new TrainingReport(
                schema,
                CanonicalCodec.requireCanonicalUuid(
                        TrainingJson.string(object, "run_id"), "run_id"),
                state,
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "dataset_digest"), "dataset_digest"),
                CanonicalCodec.requirePositiveInt32(
                        object.get("dataset_image_count"), "dataset_image_count"),
                splitPlan,
                InferenceIdentityCodec.parseModuleContentIdentity(object.get("source_module")),
                TrainingJson.modelId(object.get("selected_model_id"), "selected_model_id"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "starting_weight_digest"),
                        "starting_weight_digest"),
                initialization,
                sourceOutput,
                CanonicalCodec.requirePositiveFiniteDouble(
                        object.get("source_model_microns_per_pixel"),
                        "source_model_microns_per_pixel"),
                TrainingJson.enumValue(
                        object.get("result_form"),
                        Set.of("selected_model_only", "complete_source_workflow"),
                        "result_form"),
                TrainingJson.text(
                        object.get("result_display_name"),
                        "result_display_name",
                        bounded.maxTextBytes()),
                TrainingJson.stableId(object.get("result_inference_gpu"), "result_inference_gpu"),
                task,
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("trainer_build")),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("training_seed"), "training_seed"),
                settings,
                totalEpochs,
                fieldProfile,
                TrainingRunRequestCodec.requireInstanceThreshold(
                        TrainingRunRequestCodec.parseInstanceThreshold(object), task.outputType()),
                augmentation,
                augmentationPresentation,
                configuration,
                trainingCounts,
                validationCounts,
                policy,
                epochs,
                unavailable,
                selection,
                messages,
                failure);
    }

    /** Encode one validated report as its exact lower-snake JSON contract. */
    public static JsonObject toJson(TrainingReport report, TrainingReportLimits limits) {
        return encodeReport(parseReport(encodeReport(report), limits));
    }

    /** Encode one validated module-report response. */
    public static JsonObject toJson(
            ModuleTrainingReportResponse response, TrainingReportLimits limits) {
        Objects.requireNonNull(response, "module report response");
        JsonObject value = new JsonObject();
        value.add("module_identity", InferenceIdentityCodec.toJson(response.moduleIdentity()));
        value.addProperty("report_digest", response.reportDigest());
        value.add("report", encodeReport(response.report()));
        ModuleTrainingReportResponse checked =
                parseModuleResponse(
                        value, limits, response.moduleIdentity(), response.reportDigest());
        JsonObject result = new JsonObject();
        result.add("module_identity", InferenceIdentityCodec.toJson(checked.moduleIdentity()));
        result.addProperty("report_digest", checked.reportDigest());
        result.add("report", encodeReport(checked.report()));
        return result;
    }

    private static void validateState(
            String state,
            int totalEpochs,
            List<TrainingEpochMetricRecord> epochs,
            List<UnavailableTrainerMetric> unavailable,
            Optional<TrainingModelSelection> selection,
            Optional<GatewayErrorCodec.Error> failure) {
        if ("QUEUED".equals(state) && !epochs.isEmpty()) {
            throw new IllegalArgumentException("QUEUED report cannot contain completed epochs");
        }
        if (!TERMINAL_STATES.contains(state) && !unavailable.isEmpty()) {
            throw new IllegalArgumentException("unavailable_metrics are terminal-only");
        }
        if ("FAILED".equals(state) != failure.isPresent()) {
            throw new IllegalArgumentException("failure presence does not match FAILED state");
        }
        if (Set.of("QUEUED", "RUNNING", "CANCELLED").contains(state) && selection.isPresent()) {
            throw new IllegalArgumentException(state + " report cannot contain model_selection");
        }
        if (Set.of("FINALIZING", "SUCCEEDED").contains(state)) {
            if (selection.isEmpty()) {
                throw new IllegalArgumentException(state + " report requires model_selection");
            }
            if (epochs.isEmpty() || epochs.getLast().epoch() != totalEpochs) {
                throw new IllegalArgumentException(state + " report requires the final epoch");
            }
        }
    }

    private static void validateMetricCoverage(
            String state,
            boolean validationEnabled,
            TrainerMetricPolicy policy,
            Map<String, TrainerMetricSeries> seriesById,
            Set<String> observedIds,
            Set<String> unavailableIds) {
        Set<String> usedIds = new HashSet<>(observedIds);
        usedIds.addAll(unavailableIds);
        if (!validationEnabled
                && usedIds.stream()
                        .anyMatch(id -> "validation".equals(seriesById.get(id).partition()))) {
            throw new IllegalArgumentException(
                    "a no-validation report cannot contain validation values");
        }
        if ("SUCCEEDED".equals(state)) {
            Set<String> applicable = new HashSet<>();
            policy.series().stream()
                    .filter(series -> validationEnabled || "training".equals(series.partition()))
                    .forEach(series -> applicable.add(series.seriesId()));
            if (!usedIds.equals(applicable)) {
                throw new IllegalArgumentException(
                        "a successful report must observe or explain every applicable metric");
            }
        }
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static JsonObject encodeReport(TrainingReport report) {
        Objects.requireNonNull(report, "training report");
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", report.schemaVersion());
        value.addProperty("run_id", report.runId());
        value.addProperty("state", report.state());
        value.addProperty("dataset_digest", report.datasetDigest());
        value.addProperty("dataset_image_count", report.datasetImageCount());
        value.add("split_plan", TrainingSplitCodec.toJson(report.splitPlan()));
        value.add("source_module", InferenceIdentityCodec.toJson(report.sourceModule()));
        value.addProperty("selected_model_id", report.selectedModelId());
        value.addProperty("starting_weight_digest", report.startingWeightDigest());
        value.addProperty("class_initialization", report.classInitialization().wireValue());
        value.add("source_model_output", ModelOutputCodec.toJson(report.sourceModelOutput()));
        value.addProperty("source_model_microns_per_pixel", report.sourceModelMicronsPerPixel());
        value.addProperty("result_form", report.resultForm());
        value.addProperty("result_display_name", report.resultDisplayName());
        value.addProperty("result_inference_gpu", report.resultInferenceGpu());
        value.add("task", TrainingTaskCodec.toJson(report.task()));
        value.add("trainer_build", BuildIdentityCodec.toJson(report.trainerBuild()));
        value.addProperty("training_seed", report.trainingSeed());
        value.add("trainer_settings", TrainingCapabilityCodec.toJson(report.trainerSettings()));
        value.addProperty("total_epochs", report.totalEpochs());
        value.add("field_profile", TrainingTaskCodec.toJson(report.fieldProfile()));
        if (report.minInstancePixels() != null) {
            value.addProperty("min_instance_pixels", report.minInstancePixels());
        }
        value.add("augmentation", TrainingCapabilityCodec.toJson(report.augmentation()));
        value.add(
                "trainer_configuration",
                TrainingReportEvidenceCodec.encodeConfiguration(report.trainerConfiguration()));
        value.add(
                "training_counts",
                TrainingReportEvidenceCodec.encodeCounts(report.trainingCounts()));
        report.validationCounts()
                .ifPresent(
                        item ->
                                value.add(
                                        "validation_counts",
                                        TrainingReportEvidenceCodec.encodeCounts(item)));
        value.add("metric_policy", TrainingMetricReportCodec.encodePolicy(report.metricPolicy()));
        JsonArray epochs = new JsonArray();
        report.epochs().forEach(item -> epochs.add(TrainingMetricReportCodec.encodeEpoch(item)));
        value.add("epochs", epochs);
        JsonArray unavailable = new JsonArray();
        report.unavailableMetrics()
                .forEach(
                        item -> unavailable.add(TrainingMetricReportCodec.encodeUnavailable(item)));
        value.add("unavailable_metrics", unavailable);
        report.modelSelection()
                .ifPresent(
                        item ->
                                value.add(
                                        "model_selection",
                                        TrainingMetricReportCodec.encodeSelection(item)));
        JsonArray messages = new JsonArray();
        report.messages()
                .forEach(item -> messages.add(TrainingReportEvidenceCodec.encodeMessage(item)));
        value.add("messages", messages);
        report.failure().ifPresent(item -> value.add("failure", GatewayErrorCodec.toJson(item)));
        return value;
    }

    private static JsonObject objectWithOptional(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        JsonObject object = TrainingJson.object(value, path);
        if (!object.keySet().containsAll(required)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }
}
