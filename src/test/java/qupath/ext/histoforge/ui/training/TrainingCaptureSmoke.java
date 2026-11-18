package qupath.ext.histoforge.ui.training;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Native Train preview, model selection, and split editing over disposable annotations. */
public final class TrainingCaptureSmoke {
    private static final String IMAGE_ID = "fixture-entry";
    private static final String ROI_ID = "11111111-1111-4111-8111-111111111111";

    private TrainingCaptureSmoke() {}

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {});
        var module =
                TrainingFormFixtures.module(
                        TrainingFormFixtures.capability(false), ignored -> {}, true);
        var alternate = alternateModule(module);
        var populated = imageWithAnnotations(1);
        var empty = imageWithAnnotations(0);
        var image = new AtomicReference<>(populated);
        var reads = new AtomicInteger();
        var entered = new AtomicReference<CountDownLatch>();
        var release = new AtomicReference<CountDownLatch>();
        var completion = new AtomicReference<CountDownLatch>();
        ImageLifecycle lifecycle = mock(ImageLifecycle.class);
        doAnswer(
                        invocation -> {
                            require(!Platform.isFxApplicationThread(), "builder ran on UI thread");
                            reads.incrementAndGet();
                            var input = image.get();
                            var gate = release.getAndSet(null);
                            var signal = entered.getAndSet(null);
                            if (signal != null) signal.countDown();
                            if (gate != null && !gate.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("capture gate timed out");
                            ((Runnable) invocation.getArgument(1)).run();
                            Function<ImageData<BufferedImage>, ?> inspect =
                                    invocation.getArgument(2);
                            return inspect.apply(input);
                        })
                .when(lifecycle)
                .readProjectAnnotations(any(), any(), any());
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
                        when(entry.getID()).thenReturn(IMAGE_ID);
                        when(entry.getImageName()).thenReturn("Fixture image");
                        when(entry.getMetadata()).thenReturn(Map.of());
                        when(project.getImageList()).thenReturn(List.of(entry));
                        when(project.getPath()).thenReturn(Path.of("/fixture/project.qpproj"));
                        when(qupath.getProject()).thenReturn(project);
                        when(qupath.projectProperty())
                                .thenReturn(new SimpleObjectProperty<>(project));
                        var registry = new ModulesRegistry(client);
                        registry.asObservable().setAll(module, alternate);
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
                        tab.lookup("#progressBox")
                                .visibleProperty()
                                .addListener(
                                        (observable, before, after) -> {
                                            var latch = completion.get();
                                            if (!after && latch != null)
                                                Platform.runLater(latch::countDown);
                                        });
                    });

            var firstRead = new CountDownLatch(1);
            var firstGate = new CountDownLatch(1);
            entered.set(firstRead);
            release.set(firstGate);
            completion.set(new CountDownLatch(1));
            fx(() -> controller.get().setActive(true));
            await(firstRead);
            fx(
                    () -> {
                        var picker = (ComboBox<String>) root.get().lookup("#moduleComboBox");
                        require(!picker.isDisabled(), "Preview blocked module selection");
                        require(
                                !root.get().lookup("#modelComboBox").isDisabled(),
                                "Preview blocked model selection");
                        String other =
                                picker.getValue().equals(module.ref())
                                        ? alternate.ref()
                                        : module.ref();
                        picker.setValue(other);
                        require(
                                picker.getValue().equals(other),
                                "Module selection did not apply during preview");
                        require(reads.get() == 1, "Module choice restarted an active project read");
                    });
            firstGate.countDown();
            await(completion.get());
            fx(
                    () -> {
                        var picker = (ComboBox<String>) root.get().lookup("#moduleComboBox");
                        String other =
                                picker.getValue().equals(module.ref())
                                        ? alternate.ref()
                                        : module.ref();
                        picker.setValue(other);
                        ((Spinner<Integer>) root.get().lookup("#validationPercentSpinner"))
                                .getValueFactory()
                                .setValue(0);
                        require(
                                !button(root.get(), "generateSplitButton").isDisabled(),
                                "valid capture cannot generate split");
                        button(root.get(), "generateSplitButton").fire();
                        require(
                                !button(root.get(), "exportDatasetButton").isDisabled(),
                                "valid assignment cannot export");
                        require(
                                reads.get() == 1,
                                "Module switching or split generation re-read the project");
                    });

            // The default is that QuPath class Cell trains the model's Cell label. The researcher
            // sets it to Background instead; a read that finds no ROI must not undo that.
            fx(
                    () -> {
                        require(
                                trainAsText(root.get(), "Cell").equals("Cell"),
                                "fixture class is not on its default");
                        trainAs(root.get(), "Cell").getSelectionModel().select(0);
                        require(
                                button(root.get(), "exportDatasetButton").isDisabled(),
                                "Background mapping kept a valid export");
                        trainAs(root.get(), "Cell").getSelectionModel().select(1);
                        require(
                                !button(root.get(), "exportDatasetButton").isDisabled(),
                                "Restored mapping asked for another read");
                        trainAs(root.get(), "Cell").getSelectionModel().select(0);
                        require(reads.get() == 1, "Mapping edits re-read the project");
                    });
            image.set(empty);
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(completion.get());
            fx(
                    () -> {
                        checkEmpty(root.get());
                        require(
                                trainAs(root.get(), "Cell") == null,
                                "a project without training ROIs still lists annotation classes");
                    });

            image.set(populated);
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(completion.get());
            fx(
                    () -> {
                        require(
                                trainAsText(root.get(), "Cell").startsWith("Background"),
                                "a read without training ROIs reset a Train as choice to its default");
                        trainAs(root.get(), "Cell").getSelectionModel().select(1);
                    });

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
            image.set(imageWithAnnotations(limit + 1));
            completion.set(new CountDownLatch(1));
            fx(() -> button(root.get(), "refreshProjectButton").fire());
            await(completion.get());
            fx(
                    () -> {
                        String message =
                                ((Label) root.get().lookup("#splitGuidanceLabel")).getText();
                        require(
                                message.contains(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "%,d target objects; the limit is %,d per ROI",
                                                limit + 1,
                                                limit)),
                                "missing actual count or configured limit");
                        require(message.contains(IMAGE_ID), "missing image ID");
                        require(message.contains(ROI_ID), "missing ROI ID");
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
            verify(lifecycle, never()).captureReadOnlyIf(any(), any(), any(), any(), any(), any());
            System.out.println(
                    "PASS: one preview through module and mapping edits, split reuse, empty refresh, stale response rejection and ROI-limit guidance.");
        } finally {
            fx(
                    () -> {
                        if (stage.get() != null) stage.get().close();
                    });
            Platform.exit();
        }
    }

    private static InstalledModule alternateModule(InstalledModule source) {
        var descriptor = source.descriptor();
        var identity = descriptor.identity();
        return new InstalledModule(
                source.gateway(),
                new InstalledModuleDescriptor(
                        new ModuleContentIdentity(
                                new ModuleRef("fixture", "alternate"),
                                identity.resolvedCommitSha(),
                                identity.recipeDigest(),
                                identity.contentDigest()),
                        descriptor.installedAt(),
                        descriptor.installedModuleFileBytes(),
                        descriptor.recipe(),
                        descriptor.scripts(),
                        descriptor.scriptSetDigest(),
                        descriptor.models(),
                        descriptor.readmeMarkdown()));
    }

    private static ImageData<BufferedImage> imageWithAnnotations(int count) {
        var hierarchy = new PathObjectHierarchy();
        if (count > 0) {
            var plane = ImagePlane.getDefaultPlane();
            var region =
                    PathObjects.createAnnotationObject(
                            ROIs.createRectangleROI(0, 0, 100, 100, plane));
            region.setID(UUID.fromString(ROI_ID));
            region.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
            var objectRoi = ROIs.createRectangleROI(1, 1, 5, 5, plane);
            var cell = PathClass.fromString("Cell");
            List<PathObject> objects = new ArrayList<>(count + 1);
            objects.add(region);
            for (int i = 0; i < count; i++) {
                objects.add(PathObjects.createDetectionObject(objectRoi, cell));
            }
            hierarchy.getRootObject().addChildObjects(objects);
        }
        @SuppressWarnings("unchecked")
        ImageData<BufferedImage> image = mock(ImageData.class);
        when(image.getHierarchy()).thenReturn(hierarchy);
        when(image.getServerMetadata())
                .thenReturn(new ImageServerMetadata.Builder().width(200).height(200).build());
        return image;
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

    /** The Train as picker of one QuPath class, or null when the class is not listed. */
    @SuppressWarnings("unchecked")
    private static ComboBox<Object> trainAs(Parent root, String qupathClass) {
        var table = (GridPane) root.lookup("#trainingAnnotationMapping");
        return table.getChildren().stream()
                .filter(node -> node instanceof Label label && label.getText().equals(qupathClass))
                .map(node -> (ComboBox<Object>) ((Label) node).getLabelFor())
                .findFirst()
                .orElse(null);
    }

    private static String trainAsText(Parent root, String qupathClass) {
        var picker = trainAs(root, qupathClass);
        return picker.getConverter().toString(picker.getValue());
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
