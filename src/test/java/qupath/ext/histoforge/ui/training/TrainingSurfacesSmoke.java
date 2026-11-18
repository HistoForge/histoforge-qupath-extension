package qupath.ext.histoforge.ui.training;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.ui.training.TrainingFormSmoke.applyQuPathStyle;
import static qupath.ext.histoforge.ui.training.TrainingFormSmoke.capture;

import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.TrainingDatasetGatewayClient;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingReportCodec;
import qupath.ext.histoforge.contract.TrainingRunPageCodec;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.jobs.JobRow;
import qupath.ext.histoforge.ui.jobs.JobsPoller;
import qupath.ext.histoforge.ui.jobs.JobsTabController;

/** Opt-in visual evidence for the dataset manager, Jobs, and browser report snapshots. */
public final class TrainingSurfacesSmoke {
    private TrainingSurfacesSmoke() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var fixture = TrainingFormFixtures.fixture("training-datasets-v1.json");
        TrainingApiLimits limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        var page = fixture.getAsJsonObject("datasetPage").getAsJsonObject("value").deepCopy();
        page.remove("next_cursor");
        var ready = page.getAsJsonArray("items").get(0).getAsJsonObject().deepCopy();
        ready.addProperty("in_use", false);
        ready.getAsJsonObject("record")
                .addProperty("dataset_id", "11111111-1111-4111-8111-111111111111");
        ready.getAsJsonObject("record").addProperty("dataset_digest", "e".repeat(64));
        ready.getAsJsonObject("record").addProperty("created_at", "2026-09-02T10:00:00.000Z");
        page.getAsJsonArray("items").add(ready);
        var client = mock(TrainingDatasetGatewayClient.class);
        when(client.limits()).thenReturn(limits);
        when(client.page(limits.maxPageSize(), Optional.empty()))
                .thenReturn(TrainingDatasetTransferCodec.parsePage(page, limits));

        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            Stage owner =
                    onFx(
                            () -> {
                                Stage stage = new Stage();
                                stage.setScene(new Scene(new Pane(), 1100, 620));
                                HistoforgeStyles.apply(stage.getScene());
                                stage.setTitle("HistoForge training surfaces smoke test");
                                stage.show();
                                return stage;
                            });
            var loaded = new CompletableFuture<Void>();
            DialogPane pane =
                    onFx(
                            () -> {
                                new TrainingDatasetManagerDialog(owner, client).show();
                                DialogPane content =
                                        (DialogPane)
                                                Window.getWindows().stream()
                                                        .filter(
                                                                window ->
                                                                        window != owner
                                                                                && window
                                                                                        .isShowing())
                                                        .findFirst()
                                                        .orElseThrow()
                                                        .getScene()
                                                        .getRoot();
                                TableView<?> table = (TableView<?>) content.lookup(".table-view");
                                table.itemsProperty()
                                        .addListener(
                                                (ignored, previous, current) -> {
                                                    if (current.size() == 3) loaded.complete(null);
                                                });
                                if (table.getItems().size() == 3) loaded.complete(null);
                                return content;
                            });
            loaded.get(10, TimeUnit.SECONDS);
            onFx(
                    () -> {
                        for (String theme : List.of("Light", "Dark")) {
                            applyQuPathStyle("Modena " + theme, pane);
                            checkDatasets(pane, output, theme.toLowerCase(java.util.Locale.ROOT));
                        }
                        pane.getScene().getWindow().hide();
                        checkJobs(owner, output, limits);
                        owner.close();
                        return null;
                    });
            verify(client).limits();
            verify(client).page(limits.maxPageSize(), Optional.empty());
            verifyNoMoreInteractions(client);
            writeReports(output, limits);
            System.out.println(
                    "PASS: populated dataset states, cleanup details, tooltips, Jobs rows, and report HTML. Fixture responses only; no cloud mutation.");
        } finally {
            Platform.exit();
        }
    }

    private static void checkDatasets(DialogPane pane, Path output, String theme) {
        TableView<?> table = (TableView<?>) pane.lookup(".table-view");
        table.getSelectionModel().select(0);
        require(button(pane, "Delete").isDisabled(), "In-use dataset can be deleted");
        capture(pane, output.resolve("datasets-in-use-" + theme + ".png"));
        table.getSelectionModel().select(1);
        require(!button(pane, "Retry cleanup").isDisabled(), "Cleanup retry is unavailable");
        require(
                pane.lookupAll(".label").stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(
                                label ->
                                        label.isVisible()
                                                && label.getText().contains("Cleanup failed:")),
                "Cleanup failure details are missing");
        capture(pane, output.resolve("datasets-cleanup-" + theme + ".png"));
        table.getSelectionModel().select(2);
        require(!button(pane, "Delete").isDisabled(), "Ready dataset cannot be deleted");
        capture(pane, output.resolve("datasets-ready-" + theme + ".png"));

        HBox statusHeading = (HBox) table.getColumns().getLast().getGraphic();
        InformationButton statusInfo =
                statusHeading.getChildren().stream()
                        .filter(InformationButton.class::isInstance)
                        .map(InformationButton.class::cast)
                        .findFirst()
                        .orElseThrow();
        statusInfo.fire();
        Window pinnedInfo =
                Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(window -> window.getScene() != null)
                        .filter(
                                window ->
                                        window.getScene()
                                                        .getRoot()
                                                        .lookup(".histoforge-info-surface")
                                                != null)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Clicked information did not open"));
        capture(
                pinnedInfo.getScene().getRoot(),
                output.resolve("dataset-status-info-" + theme + ".png"));
        pinnedInfo.hide();
    }

    private static void checkJobs(Stage owner, Path output, TrainingApiLimits limits)
            throws Exception {
        var page =
                TrainingFormFixtures.fixture("training-runs-v1.json")
                        .getAsJsonObject("page")
                        .getAsJsonObject("value");
        var jobs = FXCollections.<JobRow>observableArrayList();
        for (var value : page.getAsJsonArray("items")) {
            jobs.add(new JobRow.Training(TrainingRunPageCodec.parseSummary(value, limits)));
        }
        var finalizing = page.getAsJsonArray("items").get(1).getAsJsonObject().deepCopy();
        finalizing.addProperty("run_id", "66666666-6666-4666-8666-666666666666");
        finalizing.addProperty("state", "FINALIZING");
        finalizing.remove("result");
        jobs.add(new JobRow.Training(TrainingRunPageCodec.parseSummary(finalizing, limits)));
        var loader = new FXMLLoader(JobsTabController.class.getResource("jobs_tab.fxml"));
        Parent root = loader.load();
        JobsTabController controller = loader.getController();
        var poller = mock(JobsPoller.class);
        when(poller.jobs()).thenReturn(jobs);
        when(poller.hasBaseline()).thenReturn(true);
        when(poller.loadedProperty()).thenReturn(new ReadOnlyBooleanWrapper(true));
        when(poller.loadingProperty()).thenReturn(new ReadOnlyBooleanWrapper(false));
        when(poller.failedProperty()).thenReturn(new ReadOnlyBooleanWrapper(false));
        var client = mock(ModuleGatewayClient.class);
        var transport = mock(GatewayTransport.class);
        var inference = mock(qupath.ext.histoforge.ui.jobs.InferenceJobActions.class);
        when(inference.inferenceRows()).thenReturn(FXCollections.observableArrayList());
        when(inference.inferenceProblem())
                .thenReturn(new javafx.beans.property.ReadOnlyStringWrapper(""));
        controller.init(
                () -> client,
                () -> transport,
                poller,
                new ModulesRegistry(client),
                ignored -> {
                    throw new AssertionError("Unexpected module navigation");
                },
                inference);
        owner.getScene().setRoot(root);
        HistoforgeStyles.apply(owner.getScene());
        for (String theme : List.of("Light", "Dark")) {
            applyQuPathStyle("Modena " + theme, root);
            TableView<?> table = (TableView<?>) root.lookup("#jobsTable");
            require(table.getItems().size() == 4, "Training Jobs rows are missing");
            require(
                    root.lookupAll(".button").stream()
                                    .filter(Button.class::isInstance)
                                    .map(Button.class::cast)
                                    .filter(
                                            button ->
                                                    button.getText().equals("Open latest details"))
                                    .count()
                            == 4,
                    "Training details actions are missing");
            require(!button(root, "Cancel").isDisabled(), "Running job cannot be cancelled");
            capture(
                    root,
                    output.resolve("jobs-" + theme.toLowerCase(java.util.Locale.ROOT) + ".png"));
        }
        verifyNoInteractions(client, transport);
    }

    private static void writeReports(Path output, TrainingApiLimits limits) throws Exception {
        var response =
                TrainingFormFixtures.fixture("module-training-report-v1.json")
                        .getAsJsonObject("response");
        var completed =
                TrainingReportCodec.parseModuleResponse(
                        response,
                        limits.report(),
                        InferenceIdentityCodec.parseModuleContentIdentity(
                                response.get("module_identity")),
                        response.get("report_digest").getAsString());
        Files.writeString(
                output.resolve("completed-report.html"), TrainingReportHtml.render(completed));
        var value = response.getAsJsonObject("report").deepCopy();
        value.addProperty("state", "FAILED");
        value.remove("model_selection");
        value.add("messages", new JsonArray());
        var epochs = value.getAsJsonArray("epochs");
        epochs.remove(epochs.size() - 1);
        value.add(
                "failure",
                TrainingFormFixtures.fixture("training-runs-v1.json")
                        .getAsJsonObject("page")
                        .getAsJsonObject("value")
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("result")
                        .get("error"));
        var report = TrainingReportCodec.parseReport(value, limits.report());
        Files.writeString(
                output.resolve("failed-report.html"),
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                4,
                                CanonicalCodec.canonicalDigest(
                                        TrainingReportCodec.toJson(report, limits.report())),
                                report)));
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private static <T> T onFx(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task);
        Platform.runLater(result);
        return result.get(15, TimeUnit.SECONDS);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
