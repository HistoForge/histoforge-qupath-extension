package qupath.ext.histoforge.ui.modules;

import com.google.gson.JsonObject;
import java.util.function.BiConsumer;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModelSpec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.ModelDisplay;

/** Read-only projection of the validated recipe's ordered steps and script declarations. */
final class ModuleWorkflowView {
    private ModuleWorkflowView() {}

    static ScrollPane create(InstalledModule module, BiConsumer<String, String> showText) {
        var body = new VBox(15);
        body.setPadding(new Insets(20, 22, 24, 22));
        body.getChildren().add(ModuleControls.muted("Execution order from recipe.json"));
        var models = module.descriptor().recipe().getAsJsonObject("models");
        int number = 0;
        for (var step : module.recipe().steps()) {
            if (number > 0) body.getChildren().add(new Separator());
            var section = new VBox(8);
            String filename = step.isModelStep() ? step.model() : step.script();
            section.getChildren().add(ModuleControls.heading(++number + "  " + filename));
            if (step.isModelStep()) {
                model(
                        section,
                        module.recipe().models().get(step.model()),
                        models.getAsJsonObject(step.model()),
                        step);
            } else {
                script(section, filename, module.scripts().get(filename), showText);
            }
            body.getChildren().add(section);
        }
        var scroll = new ScrollPane(body);
        scroll.setId("moduleWorkflow");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("histoforge-module-scroll");
        return scroll;
    }

    private static void model(
            VBox section, ModelSpec model, JsonObject configuration, ModuleRecipe.Step step) {
        section.getChildren()
                .add(
                        ModuleControls.muted(
                                ModelDisplay.task(model.output().type())
                                        + " · "
                                        + model.input().micronsPerPixel()
                                        + " µm/px"));
        var summary = ModuleControls.fields();
        ModuleControls.field(
                summary, "Classes", String.join(", ", model.output().emittedClasses()));
        if (step.isRegionGated()) {
            ModuleControls.field(
                    summary,
                    "Within",
                    step.parentModel() + " · " + String.join(", ", step.parentClass()));
        }
        section.getChildren().add(summary);
        var fields = ModuleControls.fields();
        ModuleControls.field(fields, "Architecture", ModelDisplay.architecture(model.arch()));
        ModuleControls.field(fields, "Architecture ID", model.arch());
        ModuleControls.field(fields, "Default GPU", model.gpu());
        var weights = model.weights();
        ModuleControls.field(
                fields,
                "Weights",
                switch (weights.type()) {
                    case "vendored" -> weights.file();
                    case "referenced" -> weights.ref() + " @ " + weights.revision();
                    default -> weights.ref();
                });
        if (configuration.has("params")) {
            for (var entry : configuration.getAsJsonObject("params").entrySet()) {
                var value = entry.getValue();
                ModuleControls.field(
                        fields,
                        entry.getKey(),
                        value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                                ? value.getAsBigDecimal().stripTrailingZeros().toPlainString()
                                : value.toString());
            }
        }
        var settings = new TitledPane("Model settings", fields);
        settings.setExpanded(false);
        settings.setAnimated(false);
        section.getChildren().add(settings);
    }

    private static void script(
            VBox section, String filename, String source, BiConsumer<String, String> showText) {
        var view = new Hyperlink("View script");
        view.setPadding(Insets.EMPTY);
        view.setTooltip(
                new HistoforgeTooltip("Read " + filename + ". The script is not executed."));
        view.setOnAction(event -> showText.accept(filename, source));
        if (ModuleContract.METRICS_SCRIPT_NAME.equals(filename)) {
            var metrics = ScriptHeaderParser.parseMetrics(source);
            section.getChildren()
                    .add(
                            ModuleControls.muted(
                                    "Measurements · "
                                            + metrics.size()
                                            + (metrics.size() == 1
                                                    ? " definition"
                                                    : " definitions")));
            var table = ModuleControls.fields();
            table.getColumnConstraints().getFirst().setPercentWidth(78);
            table.getColumnConstraints().getLast().setPercentWidth(22);
            for (var metric : metrics) {
                ModuleControls.field(
                        table,
                        metric.labelOrName(),
                        metric.unitOrEmpty().isBlank() ? "—" : metric.unitOrEmpty());
            }
            section.getChildren().add(table);
        } else {
            section.getChildren().add(ModuleControls.muted("Processing script"));
            var classes = ScriptHeaderParser.parseClasses(source);
            if (!classes.isEmpty()) {
                var fields = ModuleControls.fields();
                ModuleControls.field(fields, "Classes", String.join(", ", classes));
                section.getChildren().add(fields);
            }
        }
        section.getChildren().add(view);
    }
}
