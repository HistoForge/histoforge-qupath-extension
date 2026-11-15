package qupath.ext.histoforge.ui.modules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import qupath.ext.histoforge.contract.TrainingReportTypes.BestValidationSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingPartitionCounts;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModelSpec;
import qupath.ext.histoforge.modules.ModuleRecipe;

/** Renders the fixed initial Hugging Face README for one exact module snapshot. */
final class PublishCard {

    private static final String TEMPLATE = "/qupath/ext/histoforge/publish/card.template.md";
    private static final String MISSING = "Not specified";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final String MARKDOWN_INLINE_PUNCTUATION = "\\`*_{}[]<>#|~&";

    private PublishCard() {}

    /** Render an ordinary module that has no managed-training report. */
    static String render(InstalledModule module, String targetRepo) {
        return render(module, targetRepo, Optional.empty());
    }

    /** Render the README, including managed-training evidence when the module declares it. */
    static String render(
            InstalledModule module, String targetRepo, Optional<TrainingReport> trainingReport) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(trainingReport, "training report");
        boolean declaresReport = module.recipe().trainingReportDigest() != null;
        if (declaresReport != trainingReport.isPresent()) {
            throw new IllegalArgumentException(
                    "training report presence must match the module recipe");
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("tags", tags(module));
        values.put("title", markdown(defaultIfBlank(module.name(), targetRepo)));
        // Source descriptions can survive fine-tuning; they are not result evidence.
        values.put(
                "description_block", declaresReport ? "" : descriptionBlock(module.description()));
        values.put("repository", targetRepo);
        values.put("output", outputs(module));
        values.put("classes", classes(module));
        values.put("input", inputs(module));
        values.put("architecture", architectures(module));
        values.put("base", baseEncoders(module));
        values.put("source_line", sourceLine(module.recipe().upstream()));
        values.put("dataset_line", trainingReport.map(PublishCard::datasetLine).orElse(""));
        values.put("validation_line", trainingReport.map(PublishCard::validationLine).orElse(""));
        return fill(loadTemplate(), values);
    }

    private static String tags(InstalledModule module) {
        return Stream.concat(Stream.of("histoforge"), models(module).stream().map(ModelSpec::arch))
                .filter(PublishCard::present)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static String outputs(InstalledModule module) {
        return joinDisplay(
                models(module).stream()
                        .map(ModelSpec::output)
                        .filter(Objects::nonNull)
                        .map(output -> outputType(output.type())));
    }

    private static String classes(InstalledModule module) {
        return joinDisplay(module.toModuleInfo().classifications().stream());
    }

    private static String inputs(InstalledModule module) {
        return joinDisplay(
                models(module).stream()
                        .map(ModelSpec::input)
                        .filter(Objects::nonNull)
                        .map(PublishCard::input));
    }

    private static String architectures(InstalledModule module) {
        return joinDisplay(models(module).stream().map(ModelSpec::arch));
    }

    private static String baseEncoders(InstalledModule module) {
        return joinDisplay(
                models(module).stream()
                        .map(ModelSpec::backbone)
                        .filter(Objects::nonNull)
                        .map(ModelSpec::weights)
                        .filter(Objects::nonNull)
                        .map(ModelSpec.WeightsSpec::ref));
    }

    private static Collection<ModelSpec> models(InstalledModule module) {
        ModuleRecipe recipe = module.recipe();
        return recipe == null ? List.of() : recipe.models().values();
    }

    private static String input(ModelSpec.InputSpec input) {
        String kind =
                switch (input.type()) {
                    case "field" -> "Field";
                    case "crop" -> "Parent-framed crop";
                    default -> input.type();
                };
        return kind + " at " + Double.toString(input.micronsPerPixel()) + " µm/px";
    }

    private static String outputType(String type) {
        return switch (type) {
            case "instance_segmentation" -> "Instance segmentation";
            case "instance_segmentation_and_classification" ->
                    "Instance segmentation and classification";
            case "dense_classification" -> "Dense classification";
            case "region_classification" -> "Region classification";
            default -> type.replace('_', ' ');
        };
    }

    private static String descriptionBlock(String description) {
        return present(description) ? markdown(description) + "\n\n" : "";
    }

    private static String sourceLine(String upstream) {
        return present(upstream) ? "- **Source:** " + markdown(upstream) + "\n" : "";
    }

    private static String datasetLine(TrainingReport report) {
        long labels = targetOccurrences(report.trainingCounts());
        int rois = report.trainingCounts().roiCount();
        if (report.validationCounts().isPresent()) {
            TrainingPartitionCounts validation = report.validationCounts().orElseThrow();
            labels = Math.addExact(labels, targetOccurrences(validation));
            rois = Math.addExact(rois, validation.roiCount());
        }
        return "- **Dataset:** "
                + count(
                        labels,
                        "dense_classification".equals(report.task().outputType())
                                ? "annotated region"
                                : "object label")
                + " · "
                + count(rois, "ROI")
                + " · "
                + count(report.datasetImageCount(), "slide")
                + "\n";
    }

    private static long targetOccurrences(TrainingPartitionCounts counts) {
        long total = 0;
        for (long count : counts.targetObjectCounts().values()) {
            total = Math.addExact(total, count);
        }
        return total;
    }

    private static String validationLine(TrainingReport report) {
        if (report.validationCounts().isEmpty()) {
            return "- **Validation:** Not performed\n";
        }
        if (report.modelSelection().orElse(null) instanceof BestValidationSelection selection) {
            String displayName =
                    report.metricPolicy().series().stream()
                            .filter(series -> series.seriesId().equals(selection.seriesId()))
                            .findFirst()
                            .orElseThrow()
                            .displayName();
            return "- **Validation:** "
                    + markdown(displayName)
                    + " "
                    + Double.toString(selection.value())
                    + "\n";
        }
        String primary = report.metricPolicy().primaryValidationSeriesId();
        String reason =
                report.unavailableMetrics().stream()
                        .filter(metric -> metric.seriesId().equals(primary))
                        .findFirst()
                        .map(metric -> metric.reason())
                        .orElse("Primary validation metric unavailable");
        return "- **Validation:** " + markdown(reason) + "\n";
    }

    private static String count(long value, String unit) {
        return String.format(Locale.ROOT, "%,d %s%s", value, unit, value == 1 ? "" : "s");
    }

    private static String joinDisplay(Stream<String> values) {
        String joined =
                values.filter(PublishCard::present)
                        .distinct()
                        .map(PublishCard::markdown)
                        .collect(Collectors.joining(" · "));
        return joined.isEmpty() ? MISSING : joined;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return present(value) ? value : fallback;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** Collapse line breaks and escape inline or leading block Markdown syntax. */
    private static String markdown(String value) {
        String collapsed = value.strip().replaceAll("\\s+", " ");
        StringBuilder escaped = new StringBuilder(collapsed.length());
        for (int index = 0; index < collapsed.length(); index++) {
            char current = collapsed.charAt(index);
            if (MARKDOWN_INLINE_PUNCTUATION.indexOf(current) >= 0
                    || isLeadingBlockPunctuation(collapsed, index)) {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return escaped.toString();
    }

    private static boolean isLeadingBlockPunctuation(String value, int index) {
        char current = value.charAt(index);
        if (index == 0 && (current == '-' || current == '+')) {
            return true;
        }
        if ((current != '.' && current != ')')
                || index == 0
                || index > 9
                || index + 1 >= value.length()
                || !Character.isWhitespace(value.charAt(index + 1))) {
            return false;
        }
        return value.substring(0, index).chars().allMatch(Character::isDigit);
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> placeholders = new LinkedHashSet<>();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        if (!placeholders.equals(values.keySet())) {
            throw new IllegalStateException(
                    "card template placeholders differ from renderer values: " + placeholders);
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    private static String loadTemplate() {
        try (InputStream in = PublishCard.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("missing card template: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
