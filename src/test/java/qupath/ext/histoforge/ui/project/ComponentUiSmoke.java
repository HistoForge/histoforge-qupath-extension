package qupath.ext.histoforge.ui.project;

import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.cloud.*;
import qupath.ext.histoforge.roi.*;
import qupath.ext.histoforge.util.FxRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/** Native component checks with synthetic images, isolated preferences and no external requests. */
public final class ComponentUiSmoke {
    private static Path output;
    private static final List<String> checks = new ArrayList<>();

    private ComponentUiSmoke() {}

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        var started = new CountDownLatch(1);
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.countDown();
                });
        require(started.await(10, TimeUnit.SECONDS), "JavaFX startup");
        try {
            fx(
                    () -> {
                        PathPrefs.userPathProperty().set(output.toString());
                        origins();
                        tablesAndViewer();
                        projectSelectionAndGroups();
                        cloudSaveNavigation();
                        cloudOpenOutcomes();
                        cloudMissingCredentials();
                        cloudLogin();
                        RoiToolNativeChecks.run();
                        checks.add(
                                "ROI quadrants, Shift, snapping, focus, ignored presses, jitter, bounds, interruption and rejection");
                    });
            // Completing a held login queues its callback on the actual JavaFX thread.
            fx(
                    () -> {
                        pendingLogin.complete(null);
                    });
            fx(
                    () -> {
                        require(!loginWindow.isShowing(), "closed login reopened");
                        verify(loginCredentials).cancelLogin(pendingLogin);
                        checks.add("cancelled and closed login rejects completion");
                    });
            cloudImportPagination();
            hierarchyProtection();
            synchronousFxOwnership();
            Files.writeString(
                    output.resolve("receipt.json"),
                    new com.google.gson.GsonBuilder()
                            .setPrettyPrinting()
                            .create()
                            .toJson(
                                    Map.of(
                                            "status",
                                            "PASS",
                                            "checks",
                                            checks,
                                            "java",
                                            System.getProperty("java.version"),
                                            "os",
                                            System.getProperty("os.name"),
                                            "qupath",
                                            System.getProperty("histoforge.test.qupath.version"),
                                            "artifact",
                                            artifactIdentity)));
            System.out.println("PASS: " + String.join("; ", checks));
        } finally {
            fx(
                    () -> {
                        List.copyOf(Window.getWindows()).forEach(Window::hide);
                        // The catalog is supplied by the host at runtime, not on the extension
                        // compile path.
                        ((AutoCloseable)
                                        QuPathGUI.class
                                                .getMethod("getExtensionCatalogManager")
                                                .invoke(null))
                                .close();
                    });
            Platform.exit();
        }
    }

    private static final Map<String, String> artifactIdentity = new TreeMap<>();

    private static void origins() throws Exception {
        String expected = System.getProperty("histoforge.test.distribution.artifact");
        for (Class<?> type :
                List.of(
                        CloudSlideDialog.class,
                        S3SlideImageServer.class,
                        SsoAuthFlow.class,
                        RoiToolHandler.class,
                        HierarchyListener.class,
                        ThumbnailViewer.class,
                        MetricsTableView.class,
                        FxRunner.class)) {
            Path source =
                    Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toRealPath();
            if (expected != null)
                require(
                        source.equals(Path.of(expected).toRealPath()),
                        "wrong packaged origin: " + type);
            artifactIdentity.put(type.getSimpleName(), source.toString());
        }
        if (expected != null)
            artifactIdentity.put(
                    "sha256",
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(Path.of(expected)))));
        checks.add("actual component class origins");
    }

    @SuppressWarnings("unchecked")
    private static void tablesAndViewer() throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getImageName()).thenReturn("Synthetic slide");
        var thumbnail = new WritableImage(200, 100);
        for (int y = 0; y < 100; y++)
            for (int x = 0; x < 200; x++)
                thumbnail.getPixelWriter().setArgb(x, y, 0xff000000 | x << 16 | y << 8 | 90);
        var first =
                new ImageRowModel(
                        entry,
                        "Group A",
                        "Module",
                        "test/module",
                        Set.of("one"),
                        "Ready",
                        "Saved",
                        thumbnail,
                        null);
        var second =
                new ImageRowModel(
                        entry,
                        null,
                        "Module",
                        "test/module",
                        Set.of(),
                        "Ready",
                        "Saved",
                        null,
                        null);
        var table = new TableView<ImageRowModel>();
        table.setPrefHeight(280);
        var menu = ImageTableColumns.install(table, ignored -> {});
        table.getItems().setAll(first, second);
        menu.getItems().getFirst().fire();
        require(first.selected.get() && second.selected.get(), "select all");
        menu.getItems().getLast().fire();
        require(!first.selected.get() && !second.selected.get(), "select none");
        var metrics =
                MetricsTableView.build(
                        List.of(new MetricsTableView.MetricsTableRow("Area", Map.of("a", "12.5"))),
                        List.of("a", "b"),
                        List.of("A", "B"));
        require(metrics.getColumns().get(1).getCellData(0).equals("12.5"), "metric value");
        require(
                metrics.getColumns()
                        .get(2)
                        .getCellData(0)
                        .equals(qupath.ext.histoforge.data.MetricFormat.NO_VALUE),
                "missing metric");
        var stage = new Stage();
        var icon = ToolIcons.createRoiToolIcon();
        stage.setScene(new Scene(new VBox(8, icon, table, metrics), 1050, 430));
        stage.show();
        snapshot(stage, "project-components");
        var lastRun = table.getColumns().getLast();
        var status = table.getColumns().get(6);
        first.status.set(StatusStrings.OUTPUT_STATE_INVALID);
        first.lastBatchStatus.set(StatusStrings.FAILED_PREFIX + "synthetic failure");
        table.layout();
        var resultCell = tableCell(table, lastRun, 0);
        var statusCell = tableCell(table, status, 0);
        require(resultCell.getOnMouseClicked() != null, "failure details unavailable");
        require(
                resultCell
                        .getStyleClass()
                        .contains(qupath.ext.histoforge.ui.HistoforgeStyles.ERROR),
                "failure style");
        require(
                statusCell
                        .getStyleClass()
                        .contains(qupath.ext.histoforge.ui.HistoforgeStyles.ERROR),
                "invalid output style");
        first.status.set("Ready");
        first.lastBatchStatus.set("Saved");
        table.layout();
        require(resultCell.getOnMouseClicked() == null, "successful row retained failure action");
        require(
                !resultCell
                        .getStyleClass()
                        .contains(qupath.ext.histoforge.ui.HistoforgeStyles.ERROR),
                "successful row retained failure style");
        require(resultCell.getTooltip().getText().equals("Saved"), "stale failure tooltip");
        require(
                !statusCell
                        .getStyleClass()
                        .contains(qupath.ext.histoforge.ui.HistoforgeStyles.ERROR),
                "stale invalid output style");
        var cells =
                table.lookupAll(".table-cell").stream()
                        .filter(n -> n instanceof TableCell<?, ?>)
                        .map(n -> (TableCell<?, ?>) n)
                        .filter(
                                c ->
                                        c.getTableColumn() == table.getColumns().get(1)
                                                && c.getIndex() >= 0
                                                && c.getIndex() < 2)
                        .toList();
        require(cells.size() == 2, "thumbnail cells visible");
        table.getItems().clear();
        table.layout();
        for (var cell : cells) require(cell.getGraphic() == null, "recycled thumbnail retained");
        require(
                resultCell.getText() == null
                        && resultCell.getTooltip() == null
                        && resultCell.getOnMouseClicked() == null,
                "empty row retained run details");
        var constructor =
                ThumbnailViewer.class.getDeclaredConstructor(Window.class, List.class, int.class);
        constructor.setAccessible(true);
        Object viewer = constructor.newInstance(stage, List.of(first, second), 0);
        Stage window = (Stage) field(viewer, "window");
        window.setHeight(600);
        window.show();
        snapshot(window, "thumbnail-viewer");
        ImageView image = (ImageView) field(viewer, "image");
        require(image.getImage() == thumbnail, "wrong thumbnail");
        require(image.getFitWidth() <= 200, "fit upscaled thumbnail");
        ((Button) field(viewer, "next")).fire();
        require(image.getImage() == null, "next retained thumbnail");
        require(((Button) field(viewer, "next")).isDisabled(), "last next enabled");
        ((Button) field(viewer, "previous")).fire();
        require(window.getTitle().equals("Synthetic slide · Group A · 1 ROIs"), "thumbnail title");
        require(
                ThumbnailViewer.wheelZoom(16, 1) == 16
                        && ThumbnailViewer.wheelZoom(0.05, -1) == 0.05,
                "zoom bounds");
        window.close();
        stage.close();
        checks.add("table values, selection, recycled thumbnails, fit and navigation");
    }

    private static TableCell<?, ?> tableCell(
            TableView<?> table, TableColumn<?, ?> column, int index) {
        return table.lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance)
                .map(node -> (TableCell<?, ?>) node)
                .filter(cell -> cell.getTableColumn() == column && cell.getIndex() == index)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void projectSelectionAndGroups() throws Exception {
        var loader =
                new javafx.fxml.FXMLLoader(
                        ProjectTabController.class.getResource("project_tab.fxml"));
        loader.load();
        ProjectTabController controller = spy((ProjectTabController) loader.getController());
        doNothing().when(controller).loadProjectData();
        doNothing().when(controller).invalidateMetricPresentation();
        var gui = mock(QuPathGUI.class);
        qupath.lib.projects.Project<BufferedImage> project =
                mock(qupath.lib.projects.Project.class);
        when(gui.getProject()).thenReturn(project);
        var table = (TableView<ImageRowModel>) field(controller, "imageTable");
        setField(controller, "qupath", gui);
        setField(controller, "selectMenu", ImageTableColumns.install(table, ignored -> {}));
        setField(controller, "registry", mock(qupath.ext.histoforge.ui.ModulesRegistry.class));
        setField(
                controller,
                "runCoordinator",
                mock(ProjectRunCoordinator.class, RETURNS_DEEP_STUBS));
        var scope = mock(InferenceScopeControls.class);
        when(scope.selected())
                .thenReturn(qupath.ext.histoforge.data.ProjectInferenceScope.defaults());
        setField(controller, "inferenceScopeControls", scope);
        var summary = mock(qupath.ext.histoforge.data.ProjectSummaryData.class);
        when(summary.scope())
                .thenReturn(qupath.ext.histoforge.data.ProjectInferenceScope.defaults());
        setField(controller, "summaryData", summary);
        var images = new ArrayList<qupath.ext.histoforge.data.ProjectSummaryData.ImageSummary>();
        var entries = new ArrayList<ProjectImageEntry<BufferedImage>>();
        for (String id : List.of("selected", "unchecked")) {
            ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
            var metadata = new HashMap<String, String>();
            when(entry.getID()).thenReturn(id);
            when(entry.getImageName()).thenReturn("Same display name");
            when(entry.getMetadata()).thenReturn(metadata);
            entries.add(entry);
            images.add(
                    new qupath.ext.histoforge.data.ProjectSummaryData.ImageSummary(
                            qupath.ext.histoforge.data.ProjectSummaryData.ImageMetadata.capture(
                                    entry),
                            List.of(),
                            Map.of(),
                            Set.of(),
                            false,
                            null));
        }
        when(project.getImageList()).thenReturn(entries);
        when(summary.getImageSummaries()).thenReturn(images);
        var populate = ProjectTabController.class.getDeclaredMethod("populateUI");
        populate.setAccessible(true);
        populate.invoke(controller);
        table.getItems().getFirst().selected.set(true);
        Collections.reverse(images);
        populate.invoke(controller);
        require(
                !table.getItems().getFirst().selected.get()
                        && table.getItems().getLast().selected.get(),
                "refresh selection followed index or display name instead of entry identity");
        var assign = ProjectTabController.class.getDeclaredMethod("handleAssignGroup");
        assign.setAccessible(true);
        for (Optional<String> answer :
                List.of(Optional.<String>empty(), Optional.of(" Group B "), Optional.of("  "))) {
            clearInvocations(controller, project);
            try (var dialog =
                    mockConstruction(
                            TextInputDialog.class,
                            (created, context) -> when(created.showAndWait()).thenReturn(answer))) {
                assign.invoke(controller);
                verify(controller, answer.isEmpty() ? never() : times(1))
                        .invalidateMetricPresentation();
                verify(controller, answer.isEmpty() ? never() : times(1)).loadProjectData();
                verify(project, answer.isEmpty() ? never() : times(1)).syncChanges();
                if (answer.isPresent()) {
                    String expected = answer.get().isBlank() ? null : "Group B";
                    require(
                            Objects.equals(
                                    expected,
                                    entries.getFirst()
                                            .getMetadata()
                                            .get(qupath.ext.histoforge.util.HistoforgeKeys.GROUP)),
                            "group normalization");
                }
                require(
                        entries.getLast().getMetadata().isEmpty(),
                        "group modified an unchecked image");
            }
        }
        when(summary.getImageSummaries()).thenReturn(List.of());
        populate.invoke(controller);
        require(table.getItems().isEmpty(), "empty project retained actionable rows");
        require(
                ((VBox) field(controller, "emptyStateBox")).isVisible()
                        && !((SplitPane) field(controller, "mainSplitPane")).isVisible(),
                "empty state visibility");
        checks.add(
                "refresh preserves selection by ID; group Cancel/blank/normalization use public project sync; empty project clears rows");
    }

    private static void cloudSaveNavigation() throws Exception {
        var gui = mock(QuPathGUI.class);
        var viewer = mock(QuPathViewer.class);
        when(gui.getViewer()).thenReturn(viewer);
        var credentials = mock(AwsCredentialManager.class);
        var first =
                new qupath.lib.images.ImageData<BufferedImage>(
                        new qupath.lib.images.servers.WrappedBufferedImageServer(
                                "first", new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)));
        var second =
                new qupath.lib.images.ImageData<BufferedImage>(
                        new qupath.lib.images.servers.WrappedBufferedImageServer(
                                "second", new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)));
        first.setChanged(true);
        second.setChanged(true);
        var active = new AtomicReference<>(first);
        when(viewer.getImageData()).thenAnswer(call -> active.get());
        var constructor =
                CloudSlideDialog.class.getDeclaredConstructor(
                        QuPathGUI.class, AwsCredentialManager.class);
        constructor.setAccessible(true);
        var browser = constructor.newInstance(gui, credentials);
        var save = CloudSlideDialog.class.getDeclaredMethod("saveBeforeOpening");
        save.setAccessible(true);
        var clicked = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        try (var dialogs = mockStatic(Dialogs.class)) {
            dialogs.when(Dialogs::builder).thenCallRealMethod();
            Platform.runLater(
                    () -> {
                        try {
                            active.set(second);
                            var pane =
                                    Window.getWindows().stream()
                                            .map(w -> w.getScene().getRoot())
                                            .filter(n -> n instanceof DialogPane)
                                            .map(n -> (DialogPane) n)
                                            .findFirst()
                                            .orElseThrow();
                            ((Button) pane.lookupButton(ButtonType.NO)).fire();
                            clicked.set(true);
                        } catch (Throwable failure) {
                            error.set(failure);
                        }
                    });
            require(
                    Boolean.FALSE.equals(save.invoke(browser)),
                    "save choice applied to a different image");
            require(clicked.get() && error.get() == null, "native save prompt interaction");
            require(active.get() == second && second.isChanged(), "new active image was changed");
            dialogs.verify(() -> Dialogs.showErrorMessage(eq("Open Cloud Slide"), anyString()));
        } finally {
            ((Stage) field(browser, "window")).close();
            first.close();
            second.close();
        }
        checks.add("native save prompt rejects navigation to a different unsaved image");
    }

    @SuppressWarnings("unchecked")
    private static void cloudImportPagination() throws Exception {
        var gui = mock(QuPathGUI.class);
        var credentials = mock(AwsCredentialManager.class);
        var client = mock(S3Client.class);
        var config =
                new S3ConnectionConfig(
                        "https://example.test/start", "us-east-2", "synthetic", null);
        when(credentials.isAuthenticated()).thenReturn(true);
        when(credentials.getCurrentConfig()).thenReturn(config);
        when(credentials.getS3Client()).thenReturn(client);
        var objects =
                java.util.stream.IntStream.range(0, 23)
                        .mapToObj(
                                i ->
                                        S3Object.builder()
                                                .key("slide-" + i + ".tif")
                                                .size(100L)
                                                .lastModified(java.time.Instant.EPOCH)
                                                .eTag("synthetic")
                                                .build())
                        .toList();
        when(client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(call -> new ListObjectsV2Iterable(client, call.getArgument(0)));
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .isTruncated(false)
                                .contents(objects)
                                .build());
        Project<BufferedImage> project = mock(Project.class);
        var entries = new CopyOnWriteArrayList<ProjectImageEntry<BufferedImage>>();
        when(gui.getProject()).thenReturn(project);
        when(project.getImageList()).thenAnswer(call -> List.copyOf(entries));
        when(project.addImage(any()))
                .thenAnswer(
                        call -> {
                            ServerBuilder<BufferedImage> builder = call.getArgument(0);
                            ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
                            when(entry.getURIs()).thenReturn(builder.getURIs());
                            entries.add(entry);
                            return entry;
                        });
        var loaded = new CountDownLatch(1);
        var refreshed = new Semaphore(0);
        doAnswer(
                        call -> {
                            refreshed.release();
                            return null;
                        })
                .when(gui)
                .refreshProject();
        var browser = new AtomicReference<Object>();
        var dialogs = new AtomicReference<org.mockito.MockedStatic<Dialogs>>();
        try {
            fx(
                    () -> {
                        dialogs.set(mockStatic(Dialogs.class));
                        var thumbnail = new WritableImage(80, 60);
                        var cacheType = Class.forName("qupath.ext.histoforge.cloud.ThumbnailCache");
                        var load =
                                cacheType.getDeclaredMethod(
                                        "load", String.class, String.class, String.class);
                        load.setAccessible(true);
                        try (var cache =
                                mockConstruction(
                                        cacheType,
                                        (created, context) ->
                                                when(load.invoke(
                                                                created,
                                                                anyString(),
                                                                anyString(),
                                                                anyString()))
                                                        .thenReturn(Optional.of(thumbnail)))) {
                            var constructor =
                                    CloudSlideDialog.class.getDeclaredConstructor(
                                            QuPathGUI.class, AwsCredentialManager.class);
                            constructor.setAccessible(true);
                            browser.set(constructor.newInstance(gui, credentials));
                        }
                        ((ListView<?>) field(browser.get(), "list"))
                                .getItems()
                                .addListener(
                                        (javafx.collections.ListChangeListener<Object>)
                                                change -> loaded.countDown());
                    });
            require(loaded.await(10, TimeUnit.SECONDS), "cloud listing did not complete");
            fx(
                    () -> {
                        snapshot((Stage) field(browser.get(), "window"), "cloud-browser-ready");
                        ((Button) field(browser.get(), "next")).fire();
                        ((Button) field(browser.get(), "next")).fire();
                        require(
                                field(browser.get(), "page").equals(2),
                                "cloud third page unavailable");
                        ((Set<String>) field(browser.get(), "selected")).add("slide-20.tif");
                        ((Button) field(browser.get(), "add")).fire();
                    });
            require(
                    refreshed.tryAcquire(10, TimeUnit.SECONDS),
                    "first cloud import did not complete");
            fx(
                    () -> {
                        require(
                                field(browser.get(), "page").equals(2),
                                "cloud import reset the current page: "
                                        + field(browser.get(), "page")
                                        + "; "
                                        + ((Label) field(browser.get(), "pageLabel")).getText()
                                        + "; filter="
                                        + ((ComboBox<?>) field(browser.get(), "filter")).getValue()
                                        + "; search="
                                        + ((TextField) field(browser.get(), "search")).getText()
                                        + "; window focused="
                                        + ((Stage) field(browser.get(), "window")).isFocused()
                                        + "; focus owner="
                                        + ((Stage) field(browser.get(), "window"))
                                                .getScene()
                                                .getFocusOwner());
                        require(
                                ((Set<String>) field(browser.get(), "inProject"))
                                        .contains("slide-20.tif"),
                                "cloud import did not refresh project membership");
                        require(
                                ((Set<?>) field(browser.get(), "selected")).isEmpty(),
                                "import retained selection");
                        ((ComboBox<String>) field(browser.get(), "filter"))
                                .setValue("Not in Project");
                        require(
                                field(browser.get(), "page").equals(0),
                                "new filter did not reset page");
                        ((Button) field(browser.get(), "next")).fire();
                        ((Button) field(browser.get(), "next")).fire();
                        require(
                                field(browser.get(), "page").equals(2),
                                "filtered third page unavailable");
                        ((Set<String>) field(browser.get(), "selected"))
                                .addAll(List.of("slide-21.tif", "slide-22.tif"));
                        ((Button) field(browser.get(), "add")).fire();
                    });
            require(
                    refreshed.tryAcquire(10, TimeUnit.SECONDS),
                    "second cloud import did not complete");
            fx(
                    () -> {
                        require(
                                field(browser.get(), "page").equals(1),
                                "empty last page did not clamp to previous page");
                        require(
                                ((Label) field(browser.get(), "pageLabel"))
                                        .getText()
                                        .equals("Displaying 11–20 of 20"),
                                "filtered page label or membership is stale");
                        require(
                                ((Button) field(browser.get(), "next")).isDisabled(),
                                "last page permits next");
                        snapshot((Stage) field(browser.get(), "window"), "cloud-import-page");
                        verify(project, times(2)).syncChanges();
                    });
            checks.add(
                    "cloud import preserves current page, refreshes membership and clamps removed last page");
        } finally {
            fx(
                    () -> {
                        if (browser.get() != null) ((Stage) field(browser.get(), "window")).close();
                        if (dialogs.get() != null) dialogs.get().close();
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static void cloudOpenOutcomes() throws Exception {
        for (String scenario :
                List.of(
                        "closed",
                        "closed-save",
                        "superseded",
                        "changed-connection",
                        "cancel",
                        "save-without-entry",
                        "discard",
                        "save",
                        "image-type-failure")) {
            var gui = mock(QuPathGUI.class);
            var owner = new Stage();
            owner.setScene(new Scene(new Pane(), 200, 100));
            owner.show();
            when(gui.getStage()).thenReturn(owner);
            var viewer = mock(QuPathViewer.class);
            var credentials = mock(AwsCredentialManager.class);
            var config =
                    new S3ConnectionConfig(
                            "https://example.test/start", "us-east-2", "synthetic", null);
            when(credentials.getCurrentConfig()).thenReturn(config);
            when(gui.getViewer()).thenReturn(viewer);
            when(gui.getAllViewers())
                    .thenReturn(javafx.collections.FXCollections.observableArrayList(viewer));
            var active = new AtomicReference<qupath.lib.images.ImageData<BufferedImage>>();
            when(viewer.getImageData()).thenAnswer(call -> active.get());
            doAnswer(
                            call -> {
                                active.set(call.getArgument(0));
                                return null;
                            })
                    .when(viewer)
                    .setImageData(any());
            var constructor =
                    CloudSlideDialog.class.getDeclaredConstructor(
                            QuPathGUI.class, AwsCredentialManager.class);
            constructor.setAccessible(true);
            var browser = constructor.newInstance(gui, credentials);
            setField(browser, "connection", config);
            setField(browser, "add", new Button());
            long request = (Long) field(browser, "viewGeneration");
            qupath.lib.images.ImageData<BufferedImage> candidate =
                    mock(qupath.lib.images.ImageData.class);
            when(candidate.getImageType())
                    .thenReturn(qupath.lib.images.ImageData.ImageType.BRIGHTFIELD_H_E);
            var finish =
                    CloudSlideDialog.class.getDeclaredMethod(
                            "finishOpening", long.class, qupath.lib.images.ImageData.class);
            finish.setAccessible(true);
            if (scenario.equals("closed") || scenario.equals("closed-save"))
                ((Stage) field(browser, "window")).close();
            if (scenario.equals("superseded")) request--;
            if (scenario.equals("changed-connection"))
                when(credentials.getCurrentConfig())
                        .thenReturn(
                                new S3ConnectionConfig(
                                        "https://example.test/other", "us-east-2", "other", null));
            if (scenario.equals("image-type-failure"))
                when(candidate.getImageType())
                        .thenThrow(new IllegalStateException("synthetic type failure"));
            try (var source =
                            new qupath.lib.images.servers.WrappedBufferedImageServer(
                                    "synthetic",
                                    new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB));
                    var dialogs = mockStatic(Dialogs.class)) {
                dialogs.when(Dialogs::builder).thenCallRealMethod();
                qupath.lib.images.ImageData<BufferedImage> previous =
                        mock(qupath.lib.images.ImageData.class);
                when(previous.getServer()).thenReturn(source);
                when(previous.isChanged()).thenReturn(true);
                qupath.lib.projects.Project<BufferedImage> project =
                        mock(qupath.lib.projects.Project.class);
                ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
                boolean prompt =
                        List.of("cancel", "save-without-entry", "discard", "save", "closed-save")
                                .contains(scenario);
                var promptOwned = new AtomicBoolean();
                if (prompt) {
                    active.set(previous);
                    if (scenario.equals("save") || scenario.equals("closed-save")) {
                        when(gui.getProject()).thenReturn(project);
                        when(project.getEntry(previous)).thenReturn(entry);
                    }
                    ButtonType choice =
                            switch (scenario) {
                                case "cancel" -> ButtonType.CANCEL;
                                case "discard" -> ButtonType.NO;
                                default -> ButtonType.YES;
                            };
                    Platform.runLater(
                            () -> {
                                var pane =
                                        Window.getWindows().stream()
                                                .map(w -> w.getScene().getRoot())
                                                .filter(n -> n instanceof DialogPane)
                                                .map(n -> (DialogPane) n)
                                                .findFirst()
                                                .orElseThrow();
                                promptOwned.set(
                                        ((Stage) pane.getScene().getWindow()).getOwner() == owner);
                                ((Button) pane.lookupButton(choice)).fire();
                            });
                }
                finish.invoke(browser, request, candidate);
                boolean adopted =
                        List.of("closed", "closed-save", "discard", "save", "image-type-failure")
                                .contains(scenario);
                if (prompt) require(promptOwned.get(), "save prompt owner: " + scenario);
                require((active.get() == candidate) == adopted, "cloud open result: " + scenario);
                verify(candidate, adopted ? never() : times(1)).close();
                if (scenario.equals("save") || scenario.equals("closed-save")) {
                    var order = inOrder(entry, project, viewer);
                    order.verify(entry).saveImageData(previous);
                    order.verify(project).syncChanges();
                    order.verify(viewer).setImageData(candidate);
                }
                if (scenario.equals("image-type-failure"))
                    dialogs.verify(() -> Dialogs.showErrorMessage(eq("Image Type"), anyString()));
                if (scenario.equals("save-without-entry"))
                    dialogs.verify(() -> Dialogs.showErrorMessage(eq("Save Error"), anyString()));
            } finally {
                ((Stage) field(browser, "window")).close();
                owner.close();
            }
        }
        checks.add(
                "cloud open and owned save prompt survive close; stale, Cancel and failed-save candidates close; save precedes adoption");
    }

    private static void cloudMissingCredentials() throws Exception {
        var gui = mock(QuPathGUI.class);
        var credentials = mock(AwsCredentialManager.class);
        var config =
                new S3ConnectionConfig(
                        "https://example.test/start", "us-east-2", "synthetic", null);
        var other =
                new S3ConnectionConfig("https://example.test/other", "us-east-2", "other", null);
        when(credentials.getCurrentConfig()).thenReturn(config);
        var constructor =
                CloudSlideDialog.class.getDeclaredConstructor(
                        QuPathGUI.class, AwsCredentialManager.class);
        constructor.setAccessible(true);
        var browser = constructor.newInstance(gui, credentials);
        var otherBrowser = constructor.newInstance(gui, credentials);
        var pending = new CompletableFuture<Void>();
        when(credentials.login(config)).thenReturn(pending);
        doAnswer(
                        call -> {
                            pending.cancel(false);
                            return null;
                        })
                .when(credentials)
                .invalidate();
        var signIn =
                (Button)
                        ((VBox) ((Stage) field(otherBrowser, "window")).getScene().getRoot())
                                .getChildren().stream()
                                        .filter(Button.class::isInstance)
                                        .findFirst()
                                        .orElseThrow();
        signIn.fire();
        require(signIn.getText().equals("Cancel"), "other window login not started");
        setField(browser, "connection", config);
        var client = CloudSlideDialog.class.getDeclaredMethod("client", S3ConnectionConfig.class);
        client.setAccessible(true);
        try (var dialogs = mockStatic(Dialogs.class)) {
            when(credentials.getCurrentConfig()).thenReturn(other);
            try {
                client.invoke(browser, config);
                throw new AssertionError("changed connection accepted");
            } catch (InvocationTargetException failure) {
                require(
                        failure.getCause().getMessage().equals("Cloud connection changed"),
                        "connection failure");
            }
            verify(credentials, never()).getS3Client();
            when(credentials.getCurrentConfig()).thenReturn(config);
            Exception missing;
            try {
                client.invoke(browser, config);
                throw new AssertionError("missing client accepted");
            } catch (InvocationTargetException failure) {
                missing = (Exception) failure.getCause();
            }
            var loadFailure =
                    CloudSlideDialog.class.getDeclaredMethod("loadFailure", Exception.class);
            loadFailure.setAccessible(true);
            loadFailure.invoke(browser, missing);
            verify(credentials, never()).invalidate();
            verify(credentials, never()).cancelLogin(any());
            require(!pending.isDone(), "missing credentials cancelled another window's login");
            require(
                    field(browser, "connection") == null,
                    "missing credentials did not restore sign-in form");
            loadFailure.invoke(
                    browser,
                    software.amazon.awssdk.services.s3.model.S3Exception.builder()
                            .statusCode(403)
                            .message("synthetic rejected credentials")
                            .build());
            verify(credentials).invalidate();
        } finally {
            ((Stage) field(browser, "window")).close();
            ((Stage) field(otherBrowser, "window")).close();
        }
        checks.add(
                "connection changes rejected before IO; missing credentials preserve another window's login; rejected credentials invalidate");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static CompletableFuture<Void> pendingLogin;
    private static Stage loginWindow;
    private static AwsCredentialManager loginCredentials;

    private static void cloudLogin() throws Exception {
        var qupath = mock(QuPathGUI.class);
        var owner = new Stage();
        owner.setScene(new Scene(new Pane(), 200, 100));
        owner.show();
        when(qupath.getStage()).thenReturn(owner);
        loginCredentials = mock(AwsCredentialManager.class);
        pendingLogin = new CompletableFuture<>();
        when(loginCredentials.login(any())).thenReturn(pendingLogin);
        CloudSlideDialog.show(qupath, loginCredentials);
        loginWindow =
                Window.getWindows().stream()
                        .filter(w -> w instanceof Stage s && "Open from Cloud".equals(s.getTitle()))
                        .map(w -> (Stage) w)
                        .findFirst()
                        .orElseThrow();
        var root = (VBox) loginWindow.getScene().getRoot();
        var inputs =
                root.getChildren().stream()
                        .filter(n -> n instanceof TextField)
                        .map(n -> (TextField) n)
                        .toList();
        require(inputs.size() == 4, "cloud fields");
        inputs.get(0).setText("https://example.test/start");
        inputs.get(1).setText("us-east-2");
        inputs.get(2).setText("synthetic");
        Button signIn =
                (Button)
                        root.getChildren().stream()
                                .filter(n -> n instanceof Button)
                                .findFirst()
                                .orElseThrow();
        signIn.fire();
        require(signIn.getText().equals("Cancel"), "pending login lacks cancel");
        snapshot(loginWindow, "cloud-pending-login");
        loginWindow.close();
        owner.close();
        checks.add("native cloud form and held sign-in");
    }

    private static void hierarchyProtection() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var manager = new RoiManager();
        var object =
                PathObjects.createAnnotationObject(
                        qupath.lib.roi.ROIs.createRectangleROI(
                                0, 0, 1024, 1024, ImagePlane.getDefaultPlane()));
        manager.applyHistoforgeRoiSettings(object);
        hierarchy.getRootObject().addChildObject(object);
        var listener = new HierarchyListener(manager, () -> null);
        var events = new AtomicInteger();
        listener.setOnHierarchyChanged(events::incrementAndGet);
        hierarchy.addListener(listener);
        var id = object.getID();
        var geometry = object.getROI();
        AtomicReference<org.mockito.MockedStatic<Dialogs>> dialogs = new AtomicReference<>();
        fx(
                () -> {
                    dialogs.set(mockStatic(Dialogs.class));
                    object.setLocked(false);
                    object.setPathClass(PathClass.fromString("Changed"));
                    hierarchy.fireObjectsChangedEvent(
                            ComponentUiSmoke.class, List.of(object), true);
                    require(events.get() == 0, "intermediate event callback");
                    hierarchy.fireObjectsChangedEvent(ComponentUiSmoke.class, List.of(object));
                });
        fx(
                () -> {
                    try {
                        require(
                                object.isLocked()
                                        && object.getPathClass()
                                                .equals(RoiManager.HISTOFORGE_ROI_CLASS),
                                "ROI protection");
                        require(
                                object.getID().equals(id) && object.getROI() == geometry,
                                "protection changed scientific identity");
                        require(events.get() == 1, "recursive protection notification");
                    } finally {
                        dialogs.get().close();
                    }
                });
        checks.add("finalized ROI protection without recursive notification or geometry changes");
    }

    private static void synchronousFxOwnership() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread worker =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        FxRunner.runOnFx(
                                                () -> {
                                                    entered.countDown();
                                                    try {
                                                        require(
                                                                release.await(5, TimeUnit.SECONDS),
                                                                "FX release");
                                                    } catch (InterruptedException e) {
                                                        throw new AssertionError(e);
                                                    }
                                                });
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                    } catch (Throwable e) {
                                        failure.set(e);
                                    } finally {
                                        returned.countDown();
                                    }
                                });
        require(entered.await(5, TimeUnit.SECONDS), "FX action not started");
        worker.interrupt();
        require(
                !returned.await(100, TimeUnit.MILLISECONDS),
                "interruption released ownership early");
        release.countDown();
        require(returned.await(5, TimeUnit.SECONDS), "FX action not completed");
        require(failure.get() == null && interrupted.get(), "interruption not restored");
        var expected = new IllegalStateException("synthetic");
        try {
            FxRunner.runOnFx(
                    () -> {
                        throw expected;
                    });
            throw new AssertionError("failure swallowed");
        } catch (IllegalStateException actual) {
            require(actual == expected, "failure changed");
        }
        fx(
                () -> {
                    var flag = new AtomicBoolean();
                    FxRunner.runOnFx(() -> flag.set(true));
                    require(flag.get(), "FX call not inline");
                });
        checks.add("FX ownership survives interruption; exception and inline behavior");
    }

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void snapshot(Stage window, String name) throws Exception {
        var root = window.getScene().getRoot();
        root.applyCss();
        root.layout();
        ImageIO.write(
                SwingFXUtils.fromFXImage(root.snapshot(null, null), null),
                "PNG",
                output.resolve(name + ".png").toFile());
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void fx(Action action) throws Exception {
        var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private static void require(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }
}
