package qupath.ext.histoforge.ui.training;

import static qupath.ext.histoforge.ui.training.TrainingHtml.escape;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModelOutputCodec;
import qupath.ext.histoforge.contract.TrainingReportTypes.BestValidationSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.EnabledAugmentationOperator;
import qupath.ext.histoforge.contract.TrainingReportTypes.FinalWeightsSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingReportTypes.ResolvedTrainerComponent;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricSeries;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingPartitionCounts;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingTaskCodec.FixedFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.training.TrainingRunDraft.ResultForm;

/** Self-contained, credential-free HTML rendering of one validated training report. */
final class TrainingReportHtml {

    private static final String STYLES = loadStyles();

    private TrainingReportHtml() {}

    /** Render one immutable module report without external resources or executable content. */
    static String render(ModuleTrainingReportResponse response) {
        return render(response.report(), response.reportDigest(), response.moduleIdentity());
    }

    /** Render a validated run snapshot without inventing an installed result module. */
    static String render(TrainingReportResponse response) {
        return render(response.report(), response.reportDigest(), null);
    }

    private static String render(
            TrainingReport report, String reportDigest, ModuleContentIdentity moduleIdentity) {
        StringBuilder html = new StringBuilder(16_384);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>")
                .append(escape(report.resultDisplayName()))
                .append(" training report</title><style>")
                .append(STYLES)
                .append("</style></head><body><main>");

        int completedEpochs = TrainingMetricsHtml.completedEpochs(report);
        html.append(
                        "<header class=\"report-header\"><p class=\"eyebrow\">HistoForge / Training report</p><div class=\"title-row\"><h1>")
                .append(escape(report.resultDisplayName()))
                .append("</h1><span class=\"run-status\" data-state=\"")
                .append(escape(report.state()))
                .append("\">")
                .append(stateLabel(report.state()))
                .append("</span></div><p class=\"source-line\">Source <span>")
                .append(escape(report.sourceModule().moduleRef().toString()))
                .append("</span> · Model <span>")
                .append(escape(report.selectedModelId()))
                .append("</span></p><p class=\"snapshot\">")
                .append(
                        moduleIdentity == null
                                ? "Snapshot through epoch "
                                        + completedEpochs
                                        + ". Open latest details in Jobs to refresh."
                                : "Immutable final report through epoch " + completedEpochs + ".")
                .append("</p></header>");

        html.append(
                        "<nav aria-label=\"Report sections\"><a href=\"#metrics\">Metrics</a><a href=\"#dataset\">Dataset</a><a href=\"#configuration\">Configuration</a><a href=\"#technical\">Technical details</a></nav>")
                .append(
                        "<dl class=\"run-summary\" aria-label=\"Run summary\"><div><dt>Epoch</dt><dd>")
                .append(completedEpochs)
                .append("<span class=\"stat-unit\"> / ")
                .append(report.totalEpochs())
                .append("</span></dd></div><div><dt>Training time</dt><dd>")
                .append(TrainingMetricsHtml.duration(TrainingMetricsHtml.elapsedSeconds(report)))
                .append("</dd></div><div class=\"checkpoint\"><dt>Selected checkpoint</dt><dd>")
                .append(escape(selectedCheckpoint(report)))
                .append("</dd></div></dl>");

        messages(html, report);
        html.append(TrainingMetricsHtml.render(report));

        html.append("<section id=\"dataset\"><h2>Dataset</h2><div class=\"dataset-grid\">");
        counts(html, "Training", report, report.trainingCounts());
        report.validationCounts().ifPresent(value -> counts(html, "Validation", report, value));
        html.append("</div></section>");

        html.append("<section id=\"configuration\"><h2>Training configuration</h2><dl>");
        detail(html, "Class initialization", report.classInitialization().toString());
        detail(
                html,
                "Source resolution",
                plainNumber(report.sourceModelMicronsPerPixel()) + " µm/px");
        fieldProfile(html, report.fieldProfile(), report.task().modelMicronsPerPixel());
        if (report.minInstancePixels() != null) {
            detail(
                    html,
                    "Minimum detected object area",
                    report.minInstancePixels() == 0
                            ? "No final size filter"
                            : report.minInstancePixels() + " model-grid pixels");
        }
        detail(html, "Training GPU", report.trainerSettings().trainingGpuId());
        detail(html, "Result inference GPU", report.resultInferenceGpu());
        detail(html, "Batch size", Integer.toString(report.trainerSettings().trainingBatchSize()));
        if (report.trainerSettings().flowLossWeighting() != null) {
            detail(html, "Flow loss weighting", report.trainerSettings().flowLossWeighting());
        }
        detail(
                html,
                "Field sampling",
                Boolean.TRUE.equals(report.trainerSettings().oversample())
                        ? "Rare-class oversampling; N draws with replacement for N planned fields"
                        : "Full coverage; every planned field once per epoch");
        detail(
                html,
                "Schedule",
                report.trainerSettings().warmupEpochs()
                        + " warmup + "
                        + (report.totalEpochs() - report.trainerSettings().warmupEpochs())
                        + " cosine epochs");
        boolean customized =
                !report.augmentation()
                        .enabledOperatorIds()
                        .equals(report.augmentationPresentation().defaultEnabledOperatorIds());
        detail(
                html,
                "Augmentation profile",
                (customized ? "Customized " : "")
                        + report.augmentationPresentation().baseProfileDisplayName());
        detail(
                html,
                "Enabled augmentations",
                report.augmentationPresentation().enabledOperators().isEmpty()
                        ? "None"
                        : String.join(
                                ", ",
                                report.augmentationPresentation().enabledOperators().stream()
                                        .map(EnabledAugmentationOperator::displayName)
                                        .toList()));
        html.append(
                "</dl><h3>Annotation mapping</h3><table><thead><tr><th>QuPath class</th><th>Training class</th></tr></thead><tbody>");
        report.task().annotationClassMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry ->
                                html.append("<tr><td>")
                                        .append(escape(entry.getKey()))
                                        .append("</td><td>")
                                        .append(escape(entry.getValue()))
                                        .append("</td></tr>"));
        html.append(
                "</tbody></table><p class=\"subtle\">Unmapped annotations are background. Original QuPath annotations are unchanged.</p></section>");

        provenance(html, report, reportDigest, moduleIdentity);
        html.append("</main></body></html>");
        return html.toString();
    }

    private static void counts(
            StringBuilder html,
            String name,
            TrainingReport report,
            TrainingPartitionCounts counts) {
        html.append("<article class=\"dataset-panel\"><h3>")
                .append(name)
                .append("</h3><p class=\"partition-summary\">")
                .append(partitionSize(counts))
                .append(" · ")
                .append(counts.negativeRoiCount())
                .append(counts.negativeRoiCount() == 1 ? " ROI" : " ROIs")
                .append(
                        " without targets</p><table><thead><tr><th>Target class</th><th>"
                                + ("dense_classification".equals(report.task().outputType())
                                        ? "Annotated regions"
                                        : "Objects")
                                + "</th></tr></thead><tbody>");
        for (String pathClass : report.task().targetClasses()) {
            html.append("<tr><td>")
                    .append(escape(pathClass))
                    .append("</td><td class=\"number\">")
                    .append(counts.targetObjectCounts().get(pathClass))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></article>");
    }

    private static String selectedCheckpoint(TrainingReport report) {
        if (report.modelSelection().isEmpty()) {
            return switch (report.state()) {
                case "FAILED", "CANCELLED" -> "Not selected";
                default -> "Pending";
            };
        }
        var selected = report.modelSelection().orElseThrow();
        if (selected instanceof BestValidationSelection best) {
            TrainerMetricSeries series =
                    report.metricPolicy().series().stream()
                            .filter(item -> item.seriesId().equals(best.seriesId()))
                            .findFirst()
                            .orElseThrow();
            return "Epoch "
                    + best.epoch()
                    + " · Best "
                    + series.displayName()
                    + " "
                    + TrainingMetricsHtml.displayValue(series, best.value());
        }
        FinalWeightsSelection finalWeights = (FinalWeightsSelection) selected;
        return "Epoch "
                + finalWeights.epoch()
                + " · Final weights ("
                + finalWeights.reason().replace('_', ' ')
                + ")";
    }

    private static void fieldProfile(
            StringBuilder html, TrainingFieldProfile profile, double micronsPerPixel) {
        detail(html, "Model resolution", plainNumber(micronsPerPixel) + " µm/px");
        if (profile instanceof FixedFieldProfile fixed) {
            detail(
                    html,
                    "Model view",
                    fixed.sideModelPx()
                            + " x "
                            + fixed.sideModelPx()
                            + " px ("
                            + plainNumber(fixed.sideModelPx() * micronsPerPixel)
                            + " µm per side)");
            detail(html, "Small ROI context", yesNo(fixed.smallRoiContext()));
            detail(html, "Tile overlap", plainNumber(fixed.tileOverlap()));
            return;
        }
        VariableFieldProfile variable = (VariableFieldProfile) profile;
        detail(
                html,
                "Model view range",
                variable.minSideModelPx()
                        + " to "
                        + variable.maxSideModelPx()
                        + " px ("
                        + plainNumber(variable.minSideModelPx() * micronsPerPixel)
                        + " to "
                        + plainNumber(variable.maxSideModelPx() * micronsPerPixel)
                        + " µm per side)");
        detail(
                html,
                "Large-region tile",
                variable.preferredTileSideModelPx()
                        + " px ("
                        + plainNumber(variable.preferredTileSideModelPx() * micronsPerPixel)
                        + " µm per side)");
        detail(html, "Small ROI context", yesNo(variable.smallRoiContext()));
        detail(html, "Tile overlap", plainNumber(variable.tileOverlap()));
    }

    private static void messages(StringBuilder html, TrainingReport report) {
        if (report.messages().isEmpty() && report.failure().isEmpty()) {
            return;
        }
        html.append("<section class=\"messages\" aria-label=\"Run messages\">");
        report.messages()
                .forEach(
                        item ->
                                html.append("<p class=\"message ")
                                        .append(escape(item.severity()))
                                        .append("\"><strong>")
                                        .append(escape(capitalize(item.severity())))
                                        .append(":</strong> ")
                                        .append(escape(item.message()))
                                        .append("</p>"));
        report.failure()
                .ifPresent(
                        failure ->
                                html.append(
                                                "<p class=\"message failure\"><strong>Failure:</strong> ")
                                        .append(escape(failure.message()))
                                        .append("</p>"));
        html.append("</section>");
    }

    private static void provenance(
            StringBuilder html,
            TrainingReport report,
            String reportDigest,
            ModuleContentIdentity moduleIdentity) {
        html.append("<section id=\"technical\"><details><summary>Technical details</summary><dl>");
        detail(html, "Run ID", report.runId());
        detail(html, "Report digest", reportDigest);
        detail(html, "Dataset digest", report.datasetDigest());
        if (moduleIdentity != null) {
            detail(html, "Module content digest", moduleIdentity.contentDigest());
        }
        detail(html, "Source module", report.sourceModule().moduleRef().toString());
        detail(html, "Source content digest", report.sourceModule().contentDigest());
        detail(html, "Selected model", report.selectedModelId());
        detail(html, "Starting weight digest", report.startingWeightDigest());
        detail(
                html,
                "Source class schema",
                canonicalJson(ModelOutputCodec.toJson(report.sourceModelOutput())));
        detail(
                html,
                "Target class schema",
                canonicalJson(ModelOutputCodec.toJson(report.task().modelOutput())));
        detail(html, "Trainer version", report.trainerBuild().version());
        detail(html, "Trainer artifact digest", report.trainerBuild().artifactDigest());
        detail(html, "Training seed", Long.toString(report.trainingSeed()));
        detail(html, "Result form", ResultForm.labelForWireValue(report.resultForm()));
        detail(
                html,
                "Data-loading workers",
                Long.toString(report.trainerSettings().dataLoadingWorkers()));
        detail(
                html,
                "Gradient accumulation",
                Integer.toString(report.trainerSettings().gradientAccumulationSteps()));
        detail(html, "Warmup mode", report.trainerSettings().warmupMode());
        if (report.fieldProfile() instanceof FixedFieldProfile fixed) {
            detail(html, "Aggregation", fixed.aggregation());
            detail(html, "Preprocessing", fixed.preprocessing());
        } else {
            VariableFieldProfile variable = (VariableFieldProfile) report.fieldProfile();
            detail(html, "Aggregation", variable.aggregation());
            detail(html, "Preprocessing", variable.preprocessing());
        }
        html.append("</dl><h3>Resolved trainer configuration</h3>");
        Map<String, ResolvedTrainerComponent> components =
                Map.of(
                        "Optimizer", report.trainerConfiguration().optimizer(),
                        "Scheduler", report.trainerConfiguration().scheduler(),
                        "Sampler", report.trainerConfiguration().sampler(),
                        "Objective", report.trainerConfiguration().objective(),
                        "Augmentation", report.trainerConfiguration().augmentation(),
                        "Preprocessing", report.trainerConfiguration().preprocessing());
        for (String name :
                List.of(
                        "Optimizer",
                        "Scheduler",
                        "Sampler",
                        "Objective",
                        "Augmentation",
                        "Preprocessing")) {
            ResolvedTrainerComponent component = components.get(name);
            html.append("<h4>")
                    .append(name)
                    .append(": ")
                    .append(escape(component.profileId()))
                    .append("</h4><pre>")
                    .append(escape(canonicalJson(component.parameters())))
                    .append("</pre>");
        }
        html.append("</details></section>");
    }

    private static String canonicalJson(JsonElement value) {
        return CanonicalCodec.canonicalTree(value).toString();
    }

    private static void detail(StringBuilder html, String label, String value) {
        html.append("<dt>")
                .append(escape(label))
                .append("</dt><dd>")
                .append(escape(value))
                .append("</dd>");
    }

    private static String yesNo(boolean value) {
        return value ? "Included" : "Not included";
    }

    private static String capitalize(String value) {
        return value.isEmpty()
                ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String partitionSize(TrainingPartitionCounts counts) {
        return counts.imageCount()
                + (counts.imageCount() == 1 ? " image · " : " images · ")
                + counts.roiCount()
                + (counts.roiCount() == 1 ? " ROI" : " ROIs");
    }

    private static String plainNumber(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String stateLabel(String state) {
        return switch (state) {
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "Running";
            case "FINALIZING" -> "Preparing result";
            case "SUCCEEDED" -> "Complete";
            case "FAILED" -> "Failed";
            case "CANCELLED" -> "Cancelled";
            default -> throw new IllegalArgumentException("Unknown training report state");
        };
    }

    private static String loadStyles() {
        try (var input = TrainingReportHtml.class.getResourceAsStream("training_report.css")) {
            if (input == null)
                throw new IllegalStateException("Missing training report stylesheet");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read training report stylesheet", failure);
        }
    }
}
