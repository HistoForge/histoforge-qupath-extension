package qupath.ext.histoforge.ui.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.histoforge.roi.HierarchyListener;
import qupath.lib.gui.QuPathGUI;

/** Real local-tab startup in an isolated home with no deployment or cloud requests. */
public final class MainControllerRecoverySmoke {
    private MainControllerRecoverySmoke() {}

    /** Called by the existing native Jobs smoke after JavaFX startup, from its worker thread. */
    public static void verify() throws Exception {
        String previousUserHome = System.getProperty("user.home");
        var isolatedHome = Files.createTempDirectory("histoforge-offline-entry-");
        System.setProperty("user.home", isolatedHome.toString());
        View view = null;
        try {
            view = fx(MainControllerRecoverySmoke::open);
            View current = view;
            awaitDisconnected(current);
            fx(
                    () -> {
                        TabPane tabs = (TabPane) current.root().getChildren().get(2);
                        require(
                                tabs.getTabs().stream()
                                        .map(Tab::getText)
                                        .toList()
                                        .equals(List.of("Project", "Modules", "Train", "Jobs")),
                                "Local tabs are unavailable without deployment.json");
                        Object projectOwner = field(current.controller(), "projectTabController");
                        Object jobsOwner = field(current.controller(), "jobsPoller");
                        require(projectOwner != null && jobsOwner != null, "Local owners missing");
                        require(
                                field(current.controller(), "transport") == null,
                                "Offline startup constructed an authenticated transport");
                        require(
                                field(current.controller(), "moduleClient") == null,
                                "Offline startup constructed a module client");
                        invoke(current.controller(), "selectJobsTab");
                        require(
                                tabs.getSelectionModel().getSelectedItem().getText().equals("Jobs"),
                                "Jobs cannot be opened without cloud configuration");

                        // Replace only onboarding's CLI probe; exercise the actual shell
                        // navigation.
                        try (var setup =
                                mockConstruction(
                                        SetupPane.class,
                                        (pane, context) ->
                                                when(pane.getNode()).thenReturn(new VBox()))) {
                            ((Button) current.root().lookup("#bannerButton")).fire();
                            VBox onboarding = (VBox) current.root().getChildren().get(2);
                            Button back = (Button) onboarding.getChildren().getFirst();
                            require(
                                    back.getText().equals("Back to Project and Jobs"),
                                    "Setup trapped access to local recovery");
                            current.stage().show();
                            current.root().applyCss();
                            current.root().layout();
                            var bounds = back.localToScene(back.getBoundsInLocal());
                            require(
                                    bounds.getMinX() >= 16,
                                    "Back to Project and Jobs touches the left window edge");
                            require(
                                    VBox.getMargin(back) != null
                                            && VBox.getMargin(back).getTop() >= 8,
                                    "Back navigation has no spacing above it");
                            back.fire();
                        }
                        require(
                                current.root().getChildren().get(2) == tabs,
                                "Returning from setup replaced the local tabs");
                        require(
                                field(current.controller(), "projectTabController") == projectOwner
                                        && field(current.controller(), "jobsPoller") == jobsOwner,
                                "Returning from setup replaced the recovery owners");
                        current.controller().selectProjectTab();
                        require(
                                tabs.getSelectionModel()
                                        .getSelectedItem()
                                        .getText()
                                        .equals("Project"),
                                "Project cannot be reopened without cloud configuration");
                        return null;
                    });
            awaitDisconnected(current);
            System.out.println(
                    "PASS: no-deployment Project/Jobs startup and setup return preserve local owners without opening cloud services.");
        } finally {
            if (view != null) {
                View current = view;
                fx(
                        () -> {
                            current.stage().close();
                            return null;
                        });
            }
            if (previousUserHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousUserHome);
        }
    }

    private static View open() throws Exception {
        var loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
        VBox root = loader.load();
        MainController controller = loader.getController();
        var qupath = mock(QuPathGUI.class);
        when(qupath.imageDataProperty()).thenReturn(new SimpleObjectProperty<>());
        when(qupath.projectProperty()).thenReturn(new SimpleObjectProperty<>());
        var stage = new Stage();
        stage.setScene(new Scene(root));
        controller.init(qupath, mock(HierarchyListener.class), stage);
        return new View(controller, root, stage);
    }

    private static void awaitDisconnected(View view) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(
                () ->
                        ((Label) view.root().lookup("#connectionChip"))
                                .getTooltip()
                                .getText()
                                .equals("Cloud backend not configured"))) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Offline probe did not finish");
            Thread.sleep(20);
        }
        require(
                fx(() -> view.root().getChildren().get(2) instanceof TabPane),
                "NOT_CONFIGURED hid local Project/Jobs");
    }

    private static Object field(MainController controller, String name) throws Exception {
        var field = MainController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void invoke(MainController controller, String name) throws Exception {
        var method = MainController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record View(MainController controller, VBox root, Stage stage) {}
}
