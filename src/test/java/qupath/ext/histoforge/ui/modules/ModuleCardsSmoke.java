package qupath.ext.histoforge.ui.modules;

import static org.mockito.Mockito.mock;
import static qupath.ext.histoforge.ui.training.TrainingFormSmoke.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.lib.gui.prefs.QuPathStyleManager;

/** Native module-browser checks using catalog fixtures; no projects, credentials or cloud IO. */
public final class ModuleCardsSmoke {
    private ModuleCardsSmoke() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var requests = new AtomicInteger();
        var trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trap.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        trap.start();
        var started = new CountDownLatch(1);
        var uncaught = new AtomicReference<Throwable>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    Thread.currentThread()
                            .setUncaughtExceptionHandler(
                                    (thread, error) -> uncaught.compareAndSet(null, error));
                    started.countDown();
                });
        if (!started.await(10, TimeUnit.SECONDS)) throw new AssertionError("JavaFX did not start");
        Stage stage = fx(Stage::new);
        var originalStyle = fx(() -> QuPathStyleManager.selectedStyleProperty().get());
        try {
            verifyReadme(stage, "http://127.0.0.1:" + trap.getAddress().getPort() + "/asset");
            var source =
                    TrainingFormFixtures.module(
                            TrainingFormFixtures.capability(false), ignored -> {}, true);
            String markdown =
                    TrainingFormFixtures.fixture("module-readme-v1.json")
                            .get("readme_markdown")
                            .getAsString();
            markdown +=
                    "\n\n## Wide table\n\n| Measurement | Definition | Unit | Notes |\n"
                            + "|---|---|---|---|\n"
                            + "| Count | Number of objects | objects | Per image |\n"
                            + "| Area | Area covered by the objects | µm² | A longer cell that must wrap without clipping |\n"
                            + "| Density | Objects divided by tissue area | objects/mm² | Excludes background |\n";
            var saved = fixture(source, "owner/saved", markdown, false, true);
            var missing = fixture(source, "owner/missing", null, false, false);
            var trained =
                    fixture(
                            source,
                            "workspace/run-44444444-4444-4444-8444-444444444444",
                            null,
                            true,
                            false);
            verifyDetails(stage, saved, missing, trained);
            verifyBrowser(stage, output, saved, missing, trained);
            require(requests.get() == 0, "README made automatic HTTP requests");
            if (uncaught.get() != null)
                throw new AssertionError("Uncaught JavaFX failure", uncaught.get());
            System.out.println(
                    "PASS: native module browser; README tables/links; exact workflow and source viewers; actions/reports; selection/filter/refresh; no automatic HTTP; light/dark 800/1080px");
        } finally {
            fx(
                    () -> {
                        QuPathStyleManager.selectedStyleProperty().set(originalStyle);
                        stage.close();
                        return null;
                    });
            trap.stop(0);
            Platform.exit();
        }
    }

    private static void verifyReadme(Stage stage, String url) throws Exception {
        fx(
                () -> {
                    var callbacks = new ArrayList<URI>();
                    var readme =
                            ModuleReadmeView.create(
                                            "[Methods]("
                                                    + url
                                                    + ")\n\n![Image]("
                                                    + url
                                                    + ")\n\n"
                                                    + "<img src=\""
                                                    + url
                                                    + "\"><iframe src=\""
                                                    + url
                                                    + "\"></iframe><script>fetch('"
                                                    + url
                                                    + "')</script>",
                                            "owner/module",
                                            "a".repeat(40),
                                            callbacks::add)
                                    .orElseThrow();
                    show(stage, new VBox(readme), 800, 740);
                    var links = nodes(readme.getContent(), Hyperlink.class);
                    require(links.size() == 2, "Expected two explicit browser links");
                    links.forEach(Hyperlink::fire);
                    require(
                            callbacks.equals(List.of(URI.create(url), URI.create(url))),
                            "Wrong link destinations");
                    require(
                            ModuleReadmeView.create(
                                            "---\ntags: [histoforge]\n---\n",
                                            "owner/module",
                                            "a".repeat(40))
                                    .isEmpty(),
                            "Frontmatter-only README should use fallback");
                    return null;
                });
    }

    private static void verifyDetails(
            Stage stage, InstalledModule saved, InstalledModule missing, InstalledModule trained)
            throws Exception {
        fx(
                () -> {
                    var actions = new ArrayList<String>();
                    var detail =
                            new ModuleDetailsView(
                                    saved,
                                    module -> actions.add("publish:" + module.ref()),
                                    module -> actions.add("source:" + module.ref()),
                                    true,
                                    module -> actions.add("report:" + module.ref()),
                                    module -> actions.add("delete:" + module.ref()));
                    show(stage, detail, 800, 740);
                    require(detail.lookup("#moduleReadme") != null, "Saved README missing");
                    require(
                            ((Label) detail.lookup("#moduleRevision"))
                                    .getText()
                                    .equals(
                                            "Installed revision "
                                                    + saved.descriptor()
                                                            .identity()
                                                            .resolvedCommitSha()
                                                            .orElseThrow()
                                                            .substring(0, 8)),
                            "Wrong installed revision");
                    ((MenuButton) detail.lookup("#moduleActions"))
                            .getItems()
                            .forEach(item -> item.fire());
                    require(
                            actions.equals(
                                    List.of(
                                            "publish:owner/saved",
                                            "source:owner/saved",
                                            "delete:owner/saved")),
                            "Module actions changed");
                    ((Hyperlink) detail.lookup("#moduleRecipe")).fire();
                    verifyTextDialog(
                            stage,
                            new com.google.gson.GsonBuilder()
                                    .setPrettyPrinting()
                                    .create()
                                    .toJson(saved.descriptor().recipe()));
                    selectWorkflow(detail);
                    var workflow = (ScrollPane) detail.lookup("#moduleWorkflow");
                    var labels =
                            nodes(workflow.getContent(), Label.class).stream()
                                    .map(Label::getText)
                                    .toList();
                    require(
                            labels.contains("Processing script")
                                    && labels.contains("Tubule (Cross Section)"),
                            "Processing step is missing");
                    require(
                            labels.contains("Measurements · 1 definition")
                                    && labels.contains("Count"),
                            "Metric definitions missing");
                    for (var pane : nodes(workflow.getContent(), TitledPane.class)) {
                        require(!pane.isExpanded(), "Settings should start collapsed");
                        pane.setExpanded(true);
                    }
                    var settingsLabels =
                            nodes(workflow.getContent(), Label.class).stream()
                                    .map(Label::getText)
                                    .toList();
                    require(
                            settingsLabels.contains("DINOv3 + Cellpose head")
                                    && settingsLabels.contains("dinov3-adapter-cellpose")
                                    && settingsLabels.contains("future-segmenter"),
                            "Model settings lost the readable architecture or exact identifier");
                    require(
                            nodes(workflow.getContent(), Label.class).stream()
                                    .noneMatch(label -> "3.0".equals(label.getText())),
                            "Integer params have decimals");
                    // Only user-triggered viewing: opening this script must not execute its source.
                    nodes(workflow.getContent(), Hyperlink.class).getFirst().fire();
                    verifyTextDialog(stage, saved.scripts().get("cross_section.groovy"));
                    var fallback = details(missing, actions);
                    show(stage, fallback, 800, 740);
                    require(
                            fallback.lookup("#moduleReadme") == null,
                            "Missing README unexpectedly rendered");
                    require(
                            nodes(fallback, Label.class).stream()
                                    .anyMatch(
                                            label -> label.getText().equals(missing.description())),
                            "Description fallback missing");
                    var result = details(trained, actions);
                    show(stage, result, 800, 740);
                    require(
                            result.lookup("#moduleReadme") == null,
                            "Result inherited publisher README");
                    require(
                            nodes(result, Label.class).stream()
                                    .noneMatch(
                                            label -> label.getText().equals(trained.description())),
                            "Result inherited source description");
                    require(
                            ((Label) result.lookup("#moduleRevision"))
                                    .getText()
                                    .equals("Unpublished training result"),
                            "Synthetic revision for workspace result");
                    ((Button) result.lookup("#moduleTrainingReport")).fire();
                    require(
                            actions.getLast().equals("report:" + trained.ref()),
                            "Training report action lost");
                    require(
                            ((MenuButton) result.lookup("#moduleActions"))
                                    .getItems().stream()
                                            .noneMatch(
                                                    item ->
                                                            item.getText()
                                                                    .contains("Hugging Face")),
                            "Workspace result has HF action");
                    // Reports must also remain available on a published result which has a README.
                    var published =
                            fixture(saved, "owner/published", "# Published result", true, true);
                    var publishedView = details(published, actions);
                    show(stage, publishedView, 800, 740);
                    require(
                            publishedView.lookup("#moduleReadme") != null
                                    && publishedView.lookup("#moduleTrainingReport") != null,
                            "Published result lost README or report");
                    return null;
                });
    }

    private static void verifyBrowser(
            Stage stage,
            Path output,
            InstalledModule saved,
            InstalledModule missing,
            InstalledModule trained)
            throws Exception {
        Parent browser =
                fx(
                        () -> {
                            var client = mock(ModuleGatewayClient.class);
                            var registry = new ModulesRegistry(client);
                            registry.asObservable().setAll(saved, missing, trained);
                            var loader =
                                    new FXMLLoader(
                                            ModulesTabController.class.getResource(
                                                    "modules_tab.fxml"));
                            Parent root = loader.load();
                            ModulesTabController controller = loader.getController();
                            controller.init(null, client, registry, () -> {}, () -> {});
                            show(stage, root, 1080, 740);
                            @SuppressWarnings("unchecked")
                            var list = (ListView<InstalledModule>) root.lookup("#moduleList");
                            var search = (TextField) root.lookup("#searchField");
                            require(list.getItems().size() == 3, "Catalog entries missing");
                            controller.showModule(saved.ref());
                            require(
                                    list.getSelectionModel().getSelectedItem() == saved,
                                    "Jobs result reveal selected wrong module");
                            search.setText("");
                            require(
                                    list.getSelectionModel().getSelectedItem() == saved,
                                    "Clearing filter lost selection");
                            // Same ref, new descriptor: keep selection and replace the content.
                            var updated =
                                    fixture(
                                            saved,
                                            saved.ref(),
                                            "# Updated saved documentation",
                                            false,
                                            true);
                            registry.asObservable().setAll(trained, updated, missing);
                            require(
                                    list.getSelectionModel().getSelectedItem() == updated,
                                    "Refresh retained a stale catalog object");
                            require(
                                    nodes(
                                                    ((ScrollPane) root.lookup("#moduleReadme"))
                                                            .getContent(),
                                                    Text.class)
                                            .stream()
                                            .anyMatch(
                                                    text ->
                                                            text.getText()
                                                                    .equals(
                                                                            "Updated saved documentation")),
                                    "Refresh retained stale documentation");
                            search.setText("no-such-module");
                            require(
                                    list.getItems().isEmpty()
                                            && root.lookup("#moduleDetails") == null,
                                    "Filter retained stale details");
                            search.setText("publisher description");
                            require(list.getItems().size() == 3, "Description search lost");
                            controller.showModule(trained.ref());
                            require(
                                    list.getSelectionModel().getSelectedItem() == trained,
                                    "Exact training-result reveal failed");
                            search.setText("");
                            registry.asObservable().remove(trained);
                            require(
                                    list.getSelectionModel().getSelectedItem() != trained,
                                    "Removed module remained selected");
                            registry.asObservable().clear();
                            require(
                                    root.lookup("#moduleDetails") == null,
                                    "Empty catalog retained selected module");
                            registry.asObservable().setAll(saved, missing, trained);
                            controller.showModule(saved.ref());
                            search.setText("");
                            return root;
                        });
        for (String style : List.of("Modena Light", "Modena Dark")) {
            for (int width : List.of(800, 1080)) {
                fx(
                        () -> {
                            applyStyle(style);
                            stage.setWidth(width);
                            return null;
                        });
                // Native resize events arrive between FX callbacks, not inside the callback
                // which sets Stage.width. Wait for the actual scene size before taking evidence.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (fx(() -> Math.abs(stage.getScene().getWidth() - width) > 1)) {
                    require(
                            System.nanoTime() < deadline,
                            "Native window did not reach requested width");
                    Thread.sleep(10);
                }
                fx(
                        () -> {
                            var tabs = (TabPane) browser.lookup("#moduleDetailTabs");
                            tabs.getSelectionModel().select(0);
                            browser.applyCss();
                            browser.layout();
                            var readme = (ScrollPane) browser.lookup("#moduleReadme");
                            require(
                                    nodes(readme.getContent(), GridPane.class).size() == 2,
                                    "README tables missing");
                            for (var table : nodes(readme.getContent(), GridPane.class)) {
                                var tableScroll =
                                        nodes(readme.getContent(), ScrollPane.class).stream()
                                                .filter(scroll -> scroll.getContent() == table)
                                                .findFirst()
                                                .orElseThrow();
                                require(
                                        tableScroll.getViewportBounds().getHeight() + 1
                                                >= table.getHeight(),
                                        "README table rows clipped");
                            }
                            require(
                                    Math.abs(browser.getBoundsInParent().getWidth() - width) <= 1,
                                    "Browser snapshot does not match requested window width");
                            String suffix = style.substring(7).toLowerCase() + "-" + width + ".png";
                            capture(browser, output.resolve("documentation-" + suffix));
                            tabs.getSelectionModel().select(1);
                            capture(browser, output.resolve("workflow-" + suffix));
                            return null;
                        });
            }
        }
    }

    private static ModuleDetailsView details(InstalledModule module, List<String> actions) {
        return new ModuleDetailsView(
                module,
                ignored -> {},
                ignored -> {},
                ModulesTabController.canViewOnHuggingFace(module.ref()),
                value -> actions.add("report:" + value.ref()),
                ignored -> {});
    }

    private static InstalledModule fixture(
            InstalledModule source,
            String ref,
            String markdown,
            boolean trained,
            boolean declaresClasses) {
        var descriptor = source.descriptor();
        var recipe = descriptor.recipe();
        recipe.addProperty(
                "name",
                trained
                        ? "My trained model"
                        : markdown == null
                                ? "Module without documentation"
                                : "Module with saved documentation");
        recipe.addProperty("description", "Publisher description of the source model.");
        if (trained) recipe.addProperty("trainingReportDigest", "b".repeat(64));
        else recipe.remove("trainingReportDigest");
        var steps = new JsonArray();
        for (var step : recipe.getAsJsonArray("steps")) {
            if (step.getAsJsonObject().has("script")
                    && !step.getAsJsonObject().get("script").getAsString().equals("metrics.groovy"))
                continue;
            if (step.getAsJsonObject().has("script")) {
                var processing = new JsonObject();
                processing.addProperty("script", "cross_section.groovy");
                steps.add(processing);
            }
            steps.add(step);
        }
        recipe.add("steps", steps);
        recipe.getAsJsonObject("models").getAsJsonObject("future").remove("params");
        var scripts = new HashMap<>(descriptor.scripts());
        scripts.put(
                "cross_section.groovy",
                (declaresClasses ? "/* @histoforge-classes [\"Tubule (Cross Section)\"] */\n" : "")
                        + "throw new IllegalStateException('A source viewer must not execute this script')");
        var definition = ModuleContract.parse(CanonicalCodec.canonicalBytes(recipe), scripts);
        var identity =
                new ModuleContentIdentity(
                        ModuleRef.parse(ref).orElseThrow(),
                        ref.startsWith("workspace/")
                                ? Optional.empty()
                                : Optional.of("a".repeat(40)),
                        definition.recipeDigest(),
                        descriptor.identity().contentDigest());
        return new InstalledModule(
                source.gateway(),
                new InstalledModuleDescriptor(
                        identity,
                        descriptor.installedAt(),
                        descriptor.installedModuleFileBytes(),
                        recipe,
                        scripts,
                        definition.scriptSetDigest(),
                        descriptor.models(),
                        Optional.ofNullable(markdown)));
    }

    private static void verifyTextDialog(Stage owner, String expected) {
        var dialog =
                Window.getWindows().stream()
                        .filter(window -> window != owner && window.isShowing())
                        .findFirst()
                        .orElseThrow();
        var text = nodes(dialog.getScene().getRoot(), TextArea.class).getFirst();
        require(
                !text.isEditable() && text.getText().equals(expected),
                "Source viewer changed text or enabled editing");
        dialog.hide();
    }

    private static void selectWorkflow(Parent root) {
        ((TabPane) root.lookup("#moduleDetailTabs")).getSelectionModel().select(1);
        root.applyCss();
        root.layout();
    }

    private static void show(Stage stage, Parent root, int width, int height) {
        stage.setTitle("HistoForge module browser verification — fixtures only");
        stage.setScene(new Scene(root, width, height));
        HistoforgeStyles.apply(stage.getScene());
        stage.show();
        root.applyCss();
        root.layout();
    }

    private static void applyStyle(String name) {
        QuPathStyleManager.availableStylesProperty().stream()
                .filter(style -> style.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .setStyle();
    }

    private static <T extends Node> List<T> nodes(Node node, Class<T> type) {
        var result = new ArrayList<T>();
        if (type.isInstance(node)) result.add(type.cast(node));
        if (node instanceof Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) result.addAll(nodes(child, type));
        return result;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
