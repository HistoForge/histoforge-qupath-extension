package qupath.ext.histoforge.ui.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.histoforge.client.dto.ModuleInfo;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Real Project FXML/controller callbacks with held workers and synthetic project metadata. */
final class ProjectRefreshControllerChecks {
    private ProjectRefreshControllerChecks() {}

    static void run() throws Exception {
        Fixture fixture = fx(Fixture::new);
        try {
            fx(
                    () -> {
                        fixture.controller.loadProjectData();
                        fixture.deliverSummary();
                        require(
                                !fixture.loading.isVisible(),
                                "Summary retained the loading indicator");
                        require(
                                fixture.table.isVisible() && fixture.table.getItems().size() == 1,
                                "Summary table unavailable while validation is held");
                        require(
                                fixture.metricText().equals("Checking current measurements…"),
                                "Checking state not shown after summary");
                        fixture.table.getItems().getFirst().selected.set(true);
                        checkFillHelp(fixture);
                        return null;
                    });
            Validation first = fixture.nextValidation();
            fx(
                    () -> {
                        ImageRowModel row = fixture.table.getItems().getFirst();
                        first.success.accept(fixture.summary);
                        require(
                                fixture.table.getItems().getFirst() == row && row.selected.get(),
                                "Measurement attachment rebuilt rows or discarded selection");
                        require(
                                fixture.metricText().equals("No current metrics computed yet."),
                                "Empty-cache result did not replace checking state");
                        fixture.controller.loadProjectData();
                        fixture.deliverSummary();
                        require(
                                fixture.table.getItems().getFirst().selected.get(),
                                "Summary refresh discarded checked image");
                        return null;
                    });
            Validation obsolete = fixture.nextValidation();
            fx(
                    () -> {
                        doAnswer(
                                        call -> {
                                            require(
                                                    obsolete.stopped.getAsBoolean(),
                                                    "Module metadata changed before pending validation was invalidated");
                                            return call.callRealMethod();
                                        })
                                .when(fixture.metadata)
                                .put(HistoforgeKeys.MODULE, "example/new");
                        try (var choices =
                                mockConstruction(
                                        ChoiceDialog.class,
                                        (dialog, context) ->
                                                when(dialog.showAndWait())
                                                        .thenReturn(
                                                                Optional.of(
                                                                        "New — example/new")))) {
                            var action =
                                    ProjectTabController.class.getDeclaredMethod("handleSetModule");
                            action.setAccessible(true);
                            action.invoke(fixture.controller);
                        }
                        require(
                                fixture.metadata.get(HistoforgeKeys.MODULE).equals("example/new"),
                                "Actual module-assignment handler did not update metadata");
                        require(
                                fixture.summaries.size() == 2,
                                "Module refresh captured hierarchy while obsolete validation was active");
                        var stale =
                                fixture.summary.withMetrics(List.of(), Map.of("image", "obsolete"));
                        obsolete.success.accept(stale);
                        require(
                                !fixture.metricText().contains("unavailable"),
                                "Obsolete measurement response replaced the current display");
                        require(
                                fixture.summaries.size() == 3,
                                "Newest pending refresh did not start");
                        fixture.deliverSummary();
                        require(
                                fixture.table.getItems().getFirst().moduleId.equals("example/new"),
                                "New summary restored the old module assignment");
                        return null;
                    });
            Validation latest = fixture.nextValidation();
            fx(
                    () -> {
                        latest.failure.accept(
                                new IllegalStateException("controlled validation failure"));
                        require(
                                !fixture.loading.isVisible()
                                        && fixture.table.getItems().size() == 1,
                                "Validation failure hid the accepted summary");
                        require(
                                fixture.metricText().contains("Could not validate measurements"),
                                "Validation failure did not replace checking state");
                        verify(fixture.project).syncChanges();
                        return null;
                    });
        } finally {
            fx(
                    () -> {
                        fixture.session.close();
                        fixture.stage.close();
                        return null;
                    });
        }
        System.out.println(
                "PASS: real Project controller summary/loading, selection preservation, module reassignment and stale-validation rejection.");
    }

    private static void checkFillHelp(Fixture fixture) throws Exception {
        ProjectRunCoordinator coordinator = get(fixture.controller, "runCoordinator");
        javafx.scene.control.Button fill = get(fixture.controller, "runButton");
        var refresh = ProjectTabController.class.getDeclaredMethod("refreshActionControls");
        refresh.setAccessible(true);
        refresh.invoke(fixture.controller);
        var help = fill.getTooltip();
        require(
                help != null
                        && help.getText().contains("does not check their completeness or accuracy"),
                "Ready Fill lost its explanation or limitation");
        require(help.isWrapText() && help.getMaxWidth() == 420, "Ready Fill help is not bounded");
        String ready = help.getText();
        boolean readyDisabled = fill.isDisabled();
        var bounds = fill.localToScreen(fill.getBoundsInLocal());
        help.show(fill, bounds.getMinX(), bounds.getMaxY());
        help.getScene().getRoot().applyCss();
        help.getScene().getRoot().layout();
        try {
            require(
                    help.isShowing() && help.getWidth() > 0 && help.getWidth() <= 450,
                    "Fill help exceeds its text width plus popup decoration");
        } finally {
            help.hide();
        }

        when(coordinator.jobs().blocks("image")).thenReturn(true);
        refresh.invoke(fixture.controller);
        require(
                fill.isDisabled()
                        && fill.getTooltip()
                                .getText()
                                .equals("Unfinished analysis — open Jobs to continue or stop it."),
                "Unfinished Fill lost its warning or disablement");
        when(coordinator.jobs().blocks("image")).thenReturn(false);
        refresh.invoke(fixture.controller);
        require(
                ready.equals(fill.getTooltip().getText()) && fill.isDisabled() == readyDisabled,
                "Ready Fill help or enablement was not restored");

        var activeHelp = new javafx.scene.control.Tooltip("Open Jobs fixture");
        fill.setTooltip(activeHelp);
        set(fixture.controller, "batchRunning", true);
        refresh.invoke(fixture.controller);
        require(fill.getTooltip() == activeHelp, "Refresh replaced batch-owned Fill help");
        set(fixture.controller, "batchRunning", false);
        refresh.invoke(fixture.controller);
        require(ready.equals(fill.getTooltip().getText()), "Fill help missing after the batch");
        verify(coordinator, never()).startFill();
        verify(coordinator, never()).startRedo();
    }

    @SuppressWarnings("unchecked")
    private static final class Fixture {
        final ProjectTabController controller = new ProjectTabController();
        final Project<BufferedImage> project = mock(Project.class);
        final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        final Map<String, String> metadata = spy(new HashMap<>());
        final List<Consumer<ProjectDataLoader.Result>> summaries = new ArrayList<>();
        final BlockingQueue<Validation> validations = new LinkedBlockingQueue<>();
        final TableView<ImageRowModel> table;
        final ProgressIndicator loading;
        final VBox metrics;
        final Stage stage = new Stage();
        final ProjectRefreshSession session;
        ProjectSummaryData summary;

        Fixture() throws Exception {
            var fxml = new FXMLLoader(ProjectTabController.class.getResource("project_tab.fxml"));
            fxml.setControllerFactory(type -> controller);
            Parent root = fxml.load();
            table = get(controller, "imageTable");
            loading = get(controller, "loadingIndicator");
            metrics = get(controller, "groupMetricsBox");
            stage.setScene(new Scene(root, 900, 600));
            stage.setTitle("HistoForge Project controller test");
            stage.show();
            var qupath = mock(QuPathGUI.class);
            when(qupath.getProject()).thenReturn(project);
            when(qupath.getAvailablePathClasses()).thenReturn(FXCollections.observableArrayList());
            when(project.getImageList()).thenReturn(List.of(entry));
            when(entry.getID()).thenReturn("image");
            when(entry.getImageName()).thenReturn("Synthetic image");
            when(entry.getMetadata()).thenReturn(metadata);
            when(entry.readHierarchy()).thenReturn(new PathObjectHierarchy());
            metadata.put(HistoforgeKeys.MODULE, "example/old");
            var registry = mock(ModulesRegistry.class);
            when(registry.asObservable()).thenReturn(FXCollections.observableArrayList());
            var loader = mock(ProjectDataLoader.class);
            doAnswer(
                            call -> {
                                summaries.add(call.getArgument(2));
                                return null;
                            })
                    .when(loader)
                    .loadSummary(any(), any(), any(), any());
            doAnswer(
                            call -> {
                                validations.add(
                                        new Validation(
                                                call.getArgument(3),
                                                call.getArgument(4),
                                                call.getArgument(5)));
                                return null;
                            })
                    .when(loader)
                    .validateMetrics(any(), any(), any(), any(), any(), any());
            session = new ProjectRefreshSession(loader, table::getScene);
            set(controller, "qupath", qupath);
            set(controller, "registry", registry);
            set(controller, "projectDataLoader", loader);
            set(controller, "refreshSession", session);
            set(
                    controller,
                    "runCoordinator",
                    mock(ProjectRunCoordinator.class, RETURNS_DEEP_STUBS));
            set(controller, "selectMenu", new MenuButton());
            set(
                    controller,
                    "inferenceScopeControls",
                    new InferenceScopeControls(
                            get(controller, "regionModeComboBox"),
                            get(controller, "annotationClassComboBox"),
                            () -> {}));
            set(controller, "stepsPicker", new StepsPicker(get(controller, "stepsPickerButton")));
        }

        void deliverSummary() throws Exception {
            summary =
                    ProjectSummaryData.load(
                            project, null, null, null, ProjectInferenceScope.defaults());
            summaries
                    .getLast()
                    .accept(
                            new ProjectDataLoader.Result(
                                    summary,
                                    List.of(
                                            new ModuleInfo(
                                                    "example/old", "Old", "", List.of(), List.of()),
                                            new ModuleInfo(
                                                    "example/new",
                                                    "New",
                                                    "",
                                                    List.of(),
                                                    List.of()))));
        }

        Validation nextValidation() throws InterruptedException {
            Validation next = validations.poll(10, TimeUnit.SECONDS);
            require(next != null, "Controller validation was not dispatched");
            return next;
        }

        String metricText() {
            return metrics.getChildren().isEmpty()
                    ? ""
                    : ((Label) metrics.getChildren().getFirst()).getText();
        }
    }

    private record Validation(
            BooleanSupplier stopped,
            Consumer<ProjectSummaryData> success,
            Consumer<Exception> failure) {}

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
