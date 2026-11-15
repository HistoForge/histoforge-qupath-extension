package qupath.ext.histoforge.contract;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Error;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitPlan;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;

/** Immutable Java values for the shared managed-training report contract. */
public final class TrainingReportTypes {

    private TrainingReportTypes() {}

    public record TrainerMetricSeries(
            String seriesId,
            String displayName,
            String partition,
            String valueDomain,
            String definition) {}

    public record TrainerMetricPolicy(
            List<TrainerMetricSeries> series,
            String primaryValidationSeriesId,
            String primarySelectionDirection) {
        public TrainerMetricPolicy {
            series = List.copyOf(series);
        }
    }

    public record TrainerMetricValue(String seriesId, double value) {}

    public record TrainingEpochMetricRecord(
            int epoch, long elapsedSeconds, List<TrainerMetricValue> metrics) {
        public TrainingEpochMetricRecord {
            metrics = List.copyOf(metrics);
        }
    }

    public record UnavailableTrainerMetric(String seriesId, String reason) {}

    public sealed interface TrainingModelSelection
            permits BestValidationSelection, FinalWeightsSelection {}

    public record BestValidationSelection(
            String seriesId, double value, int epoch, String selectedWeightDigest)
            implements TrainingModelSelection {}

    public record FinalWeightsSelection(String reason, int epoch, String selectedWeightDigest)
            implements TrainingModelSelection {}

    /** One versioned trainer component plus its bounded report-only parameter evidence. */
    public record ResolvedTrainerComponent(String profileId, JsonObject parameters) {
        public ResolvedTrainerComponent {
            parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
        }

        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
    }

    public record ResolvedTrainerConfiguration(
            ResolvedTrainerComponent optimizer,
            ResolvedTrainerComponent scheduler,
            ResolvedTrainerComponent sampler,
            ResolvedTrainerComponent objective,
            ResolvedTrainerComponent augmentation,
            ResolvedTrainerComponent preprocessing) {}

    public record EnabledAugmentationOperator(String operatorId, String displayName) {}

    /** Immutable user-facing names and defaults copied from the selected augmentation preset. */
    public record AugmentationPresentation(
            String baseProfileId,
            String baseProfileDisplayName,
            List<String> defaultEnabledOperatorIds,
            List<EnabledAugmentationOperator> enabledOperators) {
        public AugmentationPresentation {
            defaultEnabledOperatorIds = List.copyOf(defaultEnabledOperatorIds);
            enabledOperators = List.copyOf(enabledOperators);
        }
    }

    public record TrainingPartitionCounts(
            int imageCount,
            int roiCount,
            int negativeRoiCount,
            Map<String, Long> targetObjectCounts) {
        public TrainingPartitionCounts {
            targetObjectCounts =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    Objects.requireNonNull(
                                            targetObjectCounts, "target_object_counts")));
        }
    }

    public record TrainingReportMessage(String severity, String message) {}

    /** Complete validated report revision returned by either training-report route. */
    public record TrainingReport(
            int schemaVersion,
            String runId,
            String state,
            String datasetDigest,
            int datasetImageCount,
            TrainingSplitPlan splitPlan,
            ModuleContentIdentity sourceModule,
            String selectedModelId,
            String startingWeightDigest,
            Initialization classInitialization,
            ModelOutput sourceModelOutput,
            double sourceModelMicronsPerPixel,
            String resultForm,
            String resultDisplayName,
            String resultInferenceGpu,
            TrainingTask task,
            SoftwareBuildIdentity trainerBuild,
            long trainingSeed,
            TrainingSettings trainerSettings,
            int totalEpochs,
            TrainingFieldProfile fieldProfile,
            Integer minInstancePixels,
            TrainingAugmentationSelection augmentation,
            AugmentationPresentation augmentationPresentation,
            ResolvedTrainerConfiguration trainerConfiguration,
            TrainingPartitionCounts trainingCounts,
            Optional<TrainingPartitionCounts> validationCounts,
            TrainerMetricPolicy metricPolicy,
            List<TrainingEpochMetricRecord> epochs,
            List<UnavailableTrainerMetric> unavailableMetrics,
            Optional<TrainingModelSelection> modelSelection,
            List<TrainingReportMessage> messages,
            Optional<Error> failure) {
        public TrainingReport {
            validationCounts = Objects.requireNonNull(validationCounts, "validation_counts");
            epochs = List.copyOf(epochs);
            unavailableMetrics = List.copyOf(unavailableMetrics);
            modelSelection = Objects.requireNonNull(modelSelection, "model_selection");
            messages = List.copyOf(messages);
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    /** Immutable report from the exact module content echoed by the gateway. */
    public record ModuleTrainingReportResponse(
            ModuleContentIdentity moduleIdentity, String reportDigest, TrainingReport report) {}
}
