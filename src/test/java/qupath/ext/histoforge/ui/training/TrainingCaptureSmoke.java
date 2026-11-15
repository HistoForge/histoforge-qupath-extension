package qupath.ext.histoforge.ui.training;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PlannedImage;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.training.TrainingProjectCapture;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Native controller refresh with controlled detached captures, no user files or cloud calls. */
public final class TrainingCaptureSmoke {
    private TrainingCaptureSmoke() {}

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {});
        var module =
                TrainingFormFixtures.module(
                        TrainingFormFixtures.capability(false), ignored -> {}, true);
        PlannedImage populated =
                TrainingFormFixtures.populatedProject(module, "cells").plan().images().getFirst();
        var captured = new AtomicReference<Optional<PlannedImage>>(Optional.empty());
        var entered = new AtomicReference<CountDownLatch>();
        var release = new AtomicReference<CountDownLatch>();
        var completion = new AtomicReference<CountDownLatch>();
        var dialogFailure = new AtomicReference<Throwable>();
        ImageLifecycle lifecycle = mock(ImageLifecycle.class);
        doAnswer(
                        invocation -> {
                            require(!Platform.isFxApplicationThread(), "builder ran on UI thread");
                            var result = captured.get();
                            var gate = release.getAndSet(null);
                            var signal = entered.getAndSet(null);
                            if (signal != null) signal.countDown();
                            if (gate != null && !gate.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("capture gate timed out");
                            ((Runnable) invocation.getArgument(4)).run();
                            return result.map(
                                    image ->
                                            new ImageLifecycle.ReadOnlyImageCapture<>(
                                                    ImageLifecycle.ImageDataMaterialization
                                                            .LIVE_VIEWER,
                                                    image.capture().sourceRevision(),
                                                    Optional.of(image)));
                        })
                .when(lifecycle)
                .captureReadOnlyIf(any(), any(), any(), any(), any(), any());
        var client = mock(ModuleGatewayClient.class);
        var transport = mock(GatewayTransport.class);
        var stage = new AtomicReference<Stage>();
        var root = new AtomicReference<Parent>();
        var controller = new AtomicReference<TrainingTabController>();
        try {
            fx(
                    () -> {
                        var loader =
                                new FXMLLoader(
                                        TrainingTabController.class.getResource(
                                                "training_tab.fxml"));
                        Parent tab = loader.load();
                        root.set(tab);
                        controller.set(loader.getController());
                        var qupath = mock(QuPathGUI.class);
                        Project<BufferedImage> project = mock(Project.class);
                        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
                        when(entry.getID()).thenReturn(populated.capture().projectEntryId());
                        when(entry.getImageName()).thenReturn("Fixture image");
                        when(entry.getMetadata()).thenReturn(Map.of());
                        when(project.getImageList()).thenReturn(List.of(entry));
                        when(project.getPath()).thenReturn(Path.of("/fixture/project.qpproj"));
                        when(qupath.getProject()).thenReturn(project);
                        when(qupath.projectProperty())
                                .thenReturn(new SimpleObjectProperty<>(project));
                        var registry = new ModulesRegistry(client);
                        registry.asObservable().setAll(module);
                        var limits =
                                new ClientExecutionLimits(
                                        1000000, 1000000, 1000000, 1000000, 1000000, 1000000,
                                        1000000, 1L << 30);
                        var build =
                                new ClientBuildIdentity(
                                        new SoftwareBuildIdentity(
                                                "fixture", "a".repeat(64), "b".repeat(64)),
                                        "fixture-host",
                                        Map.of("java", "25"),
                                        limits);
                        controller
                                .get()
                                .init(
                                        qupath,
                                        transport,
                                        client,
                                        registry,
                                        () -> build,
                                        lifecycle,
                                        () -> {},
                                        () -> {});
                        var window = new Stage();
                        window.setScene(new Scene(tab, 900, 800));
                        window.show();
                        stage.set(window);
                        ((Label) tab.lookup("#availabilityLabel"))
                                .textProperty()
                                .addListener(
                                        (observable, before, after) -> {
                                            if (after.startsWith("Training ROI contains")) {
                                                Platform.runLater(
                                                        () -> {
                                                            try {
                                                                checkLimitDialog(
                                                                        stage.get(), after);
                                                            } catch (Throwable error) {
                                                                dialogFailure.set(error);
                                                            } finally {
                                                                completion.get().countDown();
                                                            }
                                                        });
                                                return;
                                            }
                                            if (after.startsWith("No training ROIs")
                                                    || after.startsWith(
                                                            "Project annotations are ready")
                                                    || after.startsWith(
                                                            "The project or training configuration changed while")) {
                                                var latch = completion.get();
                                                if (latch != null) latch.countDown();
                                            }
                                        });
                    });

            completion.set(new CountDownLatch(1));
            fx(() -> controller.get().setActive(true));
            await(completion.get());
            fx(() -> checkEmpty(root.get()));

            captured.set(Optional.of(populated));
            completion.set(new CountDownLatch(1));
            fx(
                    () -> {
                        ((Spinner<Integer>) root.get().lookup("#validationPercentSpinner"))
                                .getValueFactory()
                                .setValue(0);
                        button(root.get(), "refreshProjectButton").fire();
                    });
            await(completion.get());
            fx(
                    () -> {
                        require(
                                !button(root.get(), "generateSplitButton").isDisabled(),
                                "valid capture cannot generate split");
                        button(root.get(), "generateSplitButton").fire();
                        require(
                                !button(root.get(), "exportDatasetButton").isDisabled(),
                                "valid assignment cannot export");
                    });

            captured.set(Optional.empty());
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(completion.get());
            fx(() -> checkEmpty(root.get()));

            captured.set(Optional.of(populated));
            var capturedSignal = new CountDownLatch(1);
            var captureGate = new CountDownLatch(1);
            entered.set(capturedSignal);
            release.set(captureGate);
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(capturedSignal);
            fx(() -> controller.get().invalidateProjectCapture());
            captureGate.countDown();
            await(completion.get());
            fx(
                    () ->
                            require(
                                    button(root.get(), "generateSplitButton").isDisabled(),
                                    "stale capture accepted"));

            int limit =
                    Math.toIntExact(
                            module.gateway().trainingApiLimits().dataset().maxObjectsPerRoi());
            captured.set(Optional.of(oversized(populated, limit + 1)));
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(completion.get());
            if (dialogFailure.get() != null)
                throw new AssertionError("limit dialog", dialogFailure.get());
            fx(
                    () -> {
                        String message =
                                ((Label) root.get().lookup("#availabilityLabel")).getText();
                        require(
                                message.contains(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "%,d target objects; the limit is %,d per ROI",
                                                limit + 1,
                                                limit)),
                                "missing actual count or configured limit");
                        require(
                                message.contains(populated.capture().projectEntryId()),
                                "missing image ID");
                        require(
                                message.contains(
                                        populated.capture().rois().getFirst().sourceRoiUuid()),
                                "missing ROI ID");
                        require(
                                message.contains("Location in slide pixels:"),
                                "missing ROI location");
                        require(
                                message.contains("click Refresh in Train"),
                                "missing recovery guidance");
                        for (String id :
                                List.of(
                                        "generateSplitButton",
                                        "exportDatasetButton",
                                        "startTrainingButton")) {
                            require(
                                    button(root.get(), id).isDisabled(),
                                    "oversized capture enabled " + id);
                        }
                        require(
                                !root.get().lookup("#progressBox").isVisible(),
                                "failed capture remains busy");
                    });
            verifyNoInteractions(client, transport);
            System.out.println(
                    "PASS: native empty/valid/removed-ROI refresh, split invalidation, stale response rejection, complete ROI-limit error in dialog and tab; no user project or cloud writes.");
        } finally {
            fx(
                    () -> {
                        if (stage.get() != null) stage.get().close();
                    });
            Platform.exit();
        }
    }

    private static PlannedImage oversized(PlannedImage image, int count) {
        var capture = image.capture();
        var roi = capture.rois().getFirst();
        var example = roi.targetObjects().getFirst();
        var geometry = example.geometry();
        var objects =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(
                                index ->
                                        new TrainingObject(
                                                new java.util.UUID(0, index + 1).toString(),
                                                example.objectType(),
                                                example.pathClass(),
                                                geometry))
                        .toList();
        var oversizedRoi =
                new TrainingProjectCapture.CapturedRoi(
                        roi.sourceRoiUuid(),
                        roi.geometry(),
                        objects,
                        new TrainingProjectCapture.LabelCounts(count, 0, 0));
        return new PlannedImage(
                new TrainingProjectCapture.CapturedImage(
                        capture.projectEntryId(),
                        capture.projectEntryIdDigest(),
                        capture.projectGroup(),
                        capture.sourceRevision(),
                        capture.task(),
                        List.of(oversizedRoi),
                        capture.annotationClasses()),
                image.sourcePlan());
    }

    private static void checkLimitDialog(Stage host, String expected) {
        var dialog =
                Window.getWindows().stream()
                        .filter(window -> window != host && window.isShowing())
                        .map(window -> window.getScene().lookup(".dialog-pane"))
                        .filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("limit dialog missing"));
        try {
            require(
                    dialog.lookupAll(".label").stream()
                            .filter(Label.class::isInstance)
                            .map(Label.class::cast)
                            .anyMatch(label -> expected.equals(label.getText())),
                    "limit dialog omitted or truncated error details");
        } finally {
            ((Button) dialog.lookupButton(ButtonType.OK)).fire();
        }
    }

    private static void checkEmpty(Parent root) {
        require(
                ((Label) root.lookup("#availabilityLabel"))
                        .getText()
                        .startsWith("No training ROIs found."),
                "wrong empty guidance");
        for (String id :
                List.of("generateSplitButton", "exportDatasetButton", "startTrainingButton")) {
            require(button(root, id).isDisabled(), "empty project enabled " + id);
        }
        require(!root.lookup("#progressBox").isVisible(), "completed capture remains busy");
        require(Window.getWindows().size() == 1, "empty project opened a dialog");
    }

    private static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    private static void await(CountDownLatch latch) throws Exception {
        require(latch.await(10, TimeUnit.SECONDS), "controller completion timed out");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void fx(CheckedAction action) throws Exception {
        var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
