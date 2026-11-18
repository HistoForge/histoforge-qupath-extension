package qupath.ext.histoforge.ui.modules;

import com.google.gson.GsonBuilder;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;

/** Documentation and recipe-derived workflow for one selected, immutable catalog snapshot. */
final class ModuleDetailsView extends BorderPane {
    private final MenuItem removeAction;

    ModuleDetailsView(
            InstalledModule module,
            Consumer<InstalledModule> onPublish,
            Consumer<InstalledModule> onViewOnHf,
            boolean canViewOnHf,
            Consumer<InstalledModule> onReport,
            Consumer<InstalledModule> onDelete) {
        setId("moduleDetails");
        var name = ModuleControls.heading(module.name());
        name.getStyleClass().add("histoforge-module-title");
        Node source;
        if (canViewOnHf) {
            var link = new Hyperlink(module.ref());
            link.setPadding(Insets.EMPTY);
            link.setTooltip(
                    new HistoforgeTooltip(
                            "Open this module’s installed revision on Hugging Face."));
            link.setOnAction(event -> onViewOnHf.accept(module));
            source = link;
        } else {
            source = ModuleControls.muted(module.ref());
        }
        var heading = new VBox(5, name, source);
        heading.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        var menu = new MenuButton("⋯");
        menu.setId("moduleActions");
        menu.setAccessibleText("Actions for " + module.name());
        menu.setTooltip(new HistoforgeTooltip("Module actions"));
        if (hasReport(module))
            menu.getItems().add(action("View training report", module, onReport));
        menu.getItems().add(action("Publish…", module, onPublish));
        if (canViewOnHf) menu.getItems().add(action("View on Hugging Face", module, onViewOnHf));
        removeAction = action("Remove module…", module, onDelete);
        menu.getItems().add(removeAction);
        var header = new HBox(12, heading, menu);
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(18, 22, 16, 22));
        setTop(header);

        var documentation = new Tab("Documentation", documentation(module, onReport));
        documentation.setTooltip(
                new HistoforgeTooltip(
                        "Instructions supplied by the publisher and saved with this installed module."));
        var workflow = new Tab("Workflow", ModuleWorkflowView.create(module, this::showText));
        workflow.setTooltip(
                new HistoforgeTooltip(
                        "The models, processing steps and measurements this module runs, in order."));
        var tabs = new TabPane(documentation, workflow);
        tabs.setId("moduleDetailTabs");
        tabs.getStyleClass().add("histoforge-module-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);

        var recipe = new Hyperlink("View recipe.json");
        recipe.setId("moduleRecipe");
        recipe.setPadding(Insets.EMPTY);
        recipe.setTooltip(
                new HistoforgeTooltip(
                        "View this installed module's settings in recipe.json. This view is read-only."));
        recipe.setOnAction(
                event ->
                        showText(
                                "recipe.json — " + module.name(),
                                new GsonBuilder()
                                        .setPrettyPrinting()
                                        .create()
                                        .toJson(module.descriptor().recipe())));
        var identity = module.descriptor().identity();
        var revision =
                ModuleControls.muted(
                        identity.resolvedCommitSha()
                                .map(sha -> "Installed revision " + sha.substring(0, 8))
                                .orElse(
                                        hasReport(module)
                                                ? "Unpublished training result"
                                                : "Workspace module"));
        revision.setId("moduleRevision");
        revision.setTooltip(
                new HistoforgeTooltip(
                        identity.resolvedCommitSha()
                                        .map(sha -> "Hugging Face commit: " + sha + "\n")
                                        .orElse("")
                                + "Content SHA-256: "
                                + identity.contentDigest()));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(12, recipe, spacer, revision);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 22, 12, 22));
        setBottom(footer);
    }

    void setRemovalPending(boolean pending) {
        removeAction.setDisable(pending);
    }

    private Node documentation(InstalledModule module, Consumer<InstalledModule> onReport) {
        var descriptor = module.descriptor();
        var readme =
                descriptor
                        .readmeMarkdown()
                        .flatMap(
                                markdown ->
                                        ModuleReadmeView.create(
                                                markdown,
                                                module.ref(),
                                                descriptor
                                                        .identity()
                                                        .resolvedCommitSha()
                                                        .orElseThrow()));
        var body = new BorderPane();
        if (readme.isPresent()) {
            body.setCenter(readme.orElseThrow());
        } else {
            var fallback = new VBox(12, ModuleControls.heading("No module documentation"));
            // A newly trained result must not inherit the source model's scientific claims.
            if (hasReport(module)) {
                fallback.getChildren()
                        .add(
                                ModuleControls.muted(
                                        "Training settings and validation metrics are available in the report."));
            } else if (module.description() != null && !module.description().isBlank()) {
                fallback.getChildren().add(ModuleControls.muted(module.description()));
            }
            fallback.setPadding(new Insets(24, 22, 24, 22));
            body.setTop(fallback);
        }
        if (hasReport(module)) {
            var report = new Button("View training report");
            report.setId("moduleTrainingReport");
            report.setTooltip(
                    new HistoforgeTooltip(
                            "View this result’s training settings and validation metrics."));
            report.setOnAction(event -> onReport.accept(module));
            var row = new HBox(report);
            row.setPadding(new Insets(12, 22, 12, 22));
            body.setBottom(row);
        }
        return body;
    }

    private void showText(String title, String source) {
        var dialog = new Dialog<Void>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(title);
        var text = new TextArea(source);
        text.setEditable(false);
        text.setStyle("-fx-font-family: monospace;");
        text.setPrefSize(710, 510);
        dialog.getDialogPane().setContent(text);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.setOnShown(event -> HistoforgeStyles.apply(dialog.getDialogPane().getScene()));
        dialog.show();
    }

    private static MenuItem action(
            String label, InstalledModule module, Consumer<InstalledModule> callback) {
        var item = new MenuItem(label);
        item.setOnAction(event -> callback.accept(module));
        return item;
    }

    private static boolean hasReport(InstalledModule module) {
        return module.recipe().trainingReportDigest() != null;
    }
}
