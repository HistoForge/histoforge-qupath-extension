package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.cloud.CloudSlideListing.Slide;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.panes.ImageDetailsPane;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ServerTools;
import software.amazon.awssdk.services.s3.S3Client;

/** Owns one cloud-browser window. Network work returns detached values to its current view. */
public class CloudSlideDialog {
    private static final Logger logger = LoggerFactory.getLogger(CloudSlideDialog.class);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final QuPathGUI qupath;
    private final AwsCredentialManager credentials;
    private final ImageLifecycle images;
    private final Stage window = new Stage();
    private final VBox content = new VBox(10);
    private final ThumbnailCache cache = new ThumbnailCache();
    private final Map<Slide, Image> thumbnails = new HashMap<>();
    private final Set<Slide> loadingThumbnails = new HashSet<>();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> inProject = new HashSet<>();
    private List<Slide> slides = List.of();
    private List<Slide> filtered = List.of();
    private ListView<Slide> list;
    private TextField search;
    private ComboBox<String> filter;
    private Label pageLabel;
    private Button previous, next, add;
    private int page;
    private long viewGeneration;
    private boolean closed;
    private boolean opening;
    private CompletableFuture<Void> login;
    private S3ConnectionConfig connection;

    public static void show(QuPathGUI qupath, AwsCredentialManager credentials) {
        new CloudSlideDialog(qupath, credentials);
    }

    private CloudSlideDialog(QuPathGUI qupath, AwsCredentialManager credentials) {
        this.qupath = qupath;
        this.credentials = credentials;
        images = new ImageLifecycle(qupath);
        window.initOwner(qupath.getStage());
        window.setTitle("Open from Cloud");
        content.setPadding(new Insets(15));
        var scene = new Scene(content, 650, 550);
        HistoforgeStyles.apply(scene);
        window.setScene(scene);
        window.setOnHidden(
                event -> {
                    closed = true;
                    cancelLogin();
                });
        if (credentials.isAuthenticated()) browse();
        else setup(null);
        window.show();
    }

    private void setup(String error) {
        viewGeneration++;
        connection = null;
        slides = filtered = List.of();
        thumbnails.clear();
        loadingThumbnails.clear();
        selected.clear();
        var saved = credentials.getCurrentConfig();
        var url =
                field(
                        saved == null ? "" : saved.ssoStartUrl(),
                        "https://d-xxxxxxxxxx.awsapps.com/start");
        var region = field(saved == null ? "us-east-1" : saved.ssoRegion(), "");
        var bucket = field(saved == null ? "" : saved.bucket(), "my-slides-bucket");
        var prefix = field(saved == null ? "" : saved.keyPrefix(), "uploaded/");
        var status = new Label(error == null ? "Not connected" : error);
        status.setWrapText(true);
        HistoforgeStyles.setSemantic(
                status, error == null ? HistoforgeStyles.NEUTRAL : HistoforgeStyles.ERROR);
        var signIn = new Button("Sign In with AWS");
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setOnAction(
                event -> {
                    if (login != null) {
                        cancelLogin();
                        signIn.setText("Sign In with AWS");
                        status.setText("Login cancelled.");
                        HistoforgeStyles.setSemantic(status, HistoforgeStyles.NEUTRAL);
                        return;
                    }
                    if (url.getText().isBlank()
                            || region.getText().isBlank()
                            || bucket.getText().isBlank()) {
                        status.setText("Please fill in SSO URL, region, and bucket.");
                        HistoforgeStyles.setSemantic(status, HistoforgeStyles.ERROR);
                        return;
                    }
                    var config =
                            new S3ConnectionConfig(
                                    url.getText().strip(),
                                    region.getText().strip(),
                                    bucket.getText().strip(),
                                    prefix.getText().isBlank() ? null : prefix.getText().strip());
                    signIn.setText("Cancel");
                    status.setText("Waiting for browser login...");
                    HistoforgeStyles.setSemantic(status, HistoforgeStyles.BUSY);
                    try {
                        var attempt = credentials.login(config);
                        login = attempt;
                        attempt.whenComplete(
                                (ignored, failure) ->
                                        Platform.runLater(
                                                () -> {
                                                    if (login != attempt) return;
                                                    login = null;
                                                    if (failure == null) {
                                                        repaintOpenCloudSlides(qupath);
                                                        browse();
                                                    } else {
                                                        signIn.setText("Sign In with AWS");
                                                        if (!(failure
                                                                instanceof CancellationException)) {
                                                            status.setText(
                                                                    "Login failed: "
                                                                            + failure.getMessage());
                                                            HistoforgeStyles.setSemantic(
                                                                    status, HistoforgeStyles.ERROR);
                                                            logger.warn(
                                                                    "Cloud sign-in failed",
                                                                    failure);
                                                        }
                                                    }
                                                }));
                    } catch (RuntimeException failure) {
                        logger.warn("Could not start cloud sign-in", failure);
                        signIn.setText("Sign In with AWS");
                        status.setText("Login failed: " + failure.getMessage());
                        HistoforgeStyles.setSemantic(status, HistoforgeStyles.ERROR);
                    }
                });
        content.getChildren()
                .setAll(
                        new Label("SSO Start URL:"),
                        url,
                        new Label("AWS Region:"),
                        region,
                        new Label("Bucket:"),
                        bucket,
                        new Label("Key prefix (optional):"),
                        prefix,
                        signIn,
                        status);
    }

    private void cancelLogin() {
        var pending = login;
        login = null;
        if (pending != null) credentials.cancelLogin(pending);
    }

    static void repaintOpenCloudSlides(QuPathGUI qupath) {
        qupath.getAllViewers().stream()
                .filter(viewer -> viewer.getServer() instanceof S3SlideImageServer)
                .forEach(viewer -> viewer.repaintEntireImage());
    }

    private void browse() {
        long request = ++viewGeneration;
        connection = credentials.getCurrentConfig();
        S3ConnectionConfig config = connection;
        var header =
                new Label(
                        config.bucket()
                                + "/"
                                + (config.keyPrefix() == null ? "" : config.keyPrefix()));
        header.setStyle("-fx-font-weight: bold;");
        search = field("", "Search slides...");
        search.setOnAction(event -> applyFilter(0));
        HBox.setHgrow(search, Priority.ALWAYS);
        filter = new ComboBox<>();
        filter.getItems()
                .setAll(
                        CloudSlideListing.ALL, CloudSlideListing.SELECTED,
                        CloudSlideListing.NOT_IN_PROJECT, CloudSlideListing.IN_PROJECT);
        filter.setValue(CloudSlideListing.ALL);
        filter.setOnAction(event -> applyFilter(0));
        var searching = row(search, button("Search", () -> applyFilter(0)), filter);
        list = new ListView<>();
        list.setCellFactory(ignored -> new SlideCell());
        list.setPlaceholder(new Label("Loading slides..."));
        list.setOnMouseClicked(
                event -> {
                    if (event.getClickCount() == 2) openSlide();
                });
        VBox.setVgrow(list, Priority.ALWAYS);
        previous = button("◀ Prev", () -> showPage(page - 1));
        next = button("Next ▶", () -> showPage(page + 1));
        pageLabel = new Label();
        var pages = row(previous, pageLabel, next);
        pages.setAlignment(Pos.CENTER);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        add = button("Add to Project", this::addSlides);
        add.setDefaultButton(true);
        var actions =
                row(
                        button(
                                "Select All",
                                () -> {
                                    filtered.forEach(slide -> selected.add(slide.key()));
                                    list.refresh();
                                }),
                        button(
                                "Deselect All",
                                () -> {
                                    selected.clear();
                                    if (CloudSlideListing.SELECTED.equals(filter.getValue())) {
                                        filtered = List.of();
                                        showPage(0);
                                    } else list.refresh();
                                }),
                        spacer,
                        button(
                                "Edit Connection",
                                () -> {
                                    try {
                                        credentials.logout();
                                        setup(null);
                                    } catch (IOException failure) {
                                        logger.warn(
                                                "Could not remove saved cloud credentials",
                                                failure);
                                        setup(
                                                "Signed out for this session, but saved credentials could not be removed: "
                                                        + failure.getMessage());
                                    }
                                }),
                        add);
        content.getChildren().setAll(header, searching, list, pages, actions);
        Thread.startVirtualThread(
                () -> {
                    try {
                        var loaded = CloudSlideListing.load(client(config), config);
                        Platform.runLater(
                                () -> {
                                    if (!isCurrentView(request)) return;
                                    slides = loaded;
                                    refreshProjectMembership();
                                    applyFilter(0);
                                });
                    } catch (Exception failure) {
                        Platform.runLater(
                                () -> {
                                    if (isCurrentView(request)) loadFailure(failure);
                                });
                    }
                });
    }

    private boolean isCurrentView(long request) {
        return !closed && request == viewGeneration;
    }

    /** Refresh may perform network IO, so callers of this method are workers. */
    private S3Client client(S3ConnectionConfig expected) throws IOException {
        if (!expected.equals(credentials.getCurrentConfig()))
            throw new IOException("Cloud connection changed");
        S3Client client = credentials.getS3Client();
        if (!expected.equals(credentials.getCurrentConfig()))
            throw new IOException("Cloud connection changed");
        if (client == null) throw new MissingCredentials();
        return client;
    }

    private void applyFilter(int requestedPage) {
        filtered =
                CloudSlideListing.filter(
                        slides, search.getText(), filter.getValue(), selected, inProject);
        showPage(requestedPage);
    }

    private void showPage(int requested) {
        var result = CloudSlideListing.page(filtered, requested);
        page = result.index();
        list.getItems().setAll(result.slides());
        list.setPlaceholder(new Label("No slides found"));
        pageLabel.setText(result.label());
        previous.setDisable(page == 0);
        next.setDisable(page + 1 == result.pages());
        long request = viewGeneration;
        S3ConnectionConfig config = connection;
        for (Slide slide : result.slides()) {
            if (thumbnails.containsKey(slide) || !loadingThumbnails.add(slide)) continue;
            Thread.startVirtualThread(
                    () -> {
                        Image image = null;
                        try {
                            image =
                                    cache.load(config.bucket(), slide.key(), slide.etag())
                                            .orElse(null);
                            if (image == null) {
                                var pixels =
                                        new S3ThumbnailFetcher()
                                                .fetch(
                                                        client(config),
                                                        config.bucket(),
                                                        slide.key());
                                if (pixels != null) {
                                    cache.save(config.bucket(), slide.key(), slide.etag(), pixels);
                                    image = SwingFXUtils.toFXImage(pixels, null);
                                }
                            }
                        } catch (Exception failure) {
                            logger.debug("Thumbnail unavailable: {}", failure.getMessage());
                        }
                        Image ready = image;
                        Platform.runLater(
                                () -> {
                                    if (!isCurrentView(request)) return;
                                    loadingThumbnails.remove(slide);
                                    if (ready != null) thumbnails.put(slide, ready);
                                    list.refresh();
                                });
                    });
        }
    }

    private void openSlide() {
        Slide slide = list.getSelectionModel().getSelectedItem();
        if (slide == null || opening || add.isDisabled()) return;
        long request = viewGeneration;
        S3ConnectionConfig config = connection;
        opening = true;
        add.setDisable(true);
        Thread.startVirtualThread(
                () -> {
                    try {
                        var server =
                                new S3SlideImageServer(
                                        slide.uri(config.bucket()),
                                        client(config),
                                        config.bucket(),
                                        slide.key());
                        ImageData<BufferedImage> image;
                        try {
                            image = new ImageData<>(server);
                        } catch (RuntimeException | Error failure) {
                            try {
                                server.close();
                            } catch (Exception cleanup) {
                                failure.addSuppressed(cleanup);
                            }
                            throw failure;
                        }
                        Platform.runLater(() -> finishOpening(request, image));
                    } catch (Exception failure) {
                        logger.warn("Could not open cloud slide", failure);
                        Platform.runLater(
                                () -> {
                                    opening = false;
                                    if (request != viewGeneration) return;
                                    if (!closed) add.setDisable(false);
                                    if (authenticationFailure(failure)) expired(failure);
                                    else
                                        Dialogs.showErrorMessage(
                                                "Cloud Slide Error",
                                                "Failed to open slide: " + failure.getMessage());
                                });
                    }
                });
    }

    /** An explicit open outlives the browser window, but not a changed connection. */
    private void finishOpening(long request, ImageData<BufferedImage> image) {
        opening = false;
        if (!sameOpenRequest(request)) {
            close(image);
            return;
        }
        if (!closed) add.setDisable(false);
        if (!saveBeforeOpening() || !sameOpenRequest(request)) {
            close(image);
            return;
        }
        try {
            images.replaceCurrentViewerImage(image)
                    .ifPresent(failure -> logger.warn("Previous image cleanup failed", failure));
        } catch (IOException | RuntimeException failure) {
            logger.warn("Could not replace the viewer image with the cloud slide", failure);
            Dialogs.showErrorMessage(
                    "Cloud Slide Error", "Failed to open slide: " + failure.getMessage());
            return;
        }
        try {
            setImageType(image);
        } catch (RuntimeException failure) {
            logger.warn("Could not set the opened slide's image type", failure);
            Dialogs.showErrorMessage(
                    "Image Type",
                    "The slide is open, but its image type could not be set. Set it in QuPath's Image tab.");
        }
    }

    private boolean sameOpenRequest(long request) {
        return request == viewGeneration
                && java.util.Objects.equals(connection, credentials.getCurrentConfig());
    }

    private static final class MissingCredentials extends IOException {
        MissingCredentials() {
            super("AWS credentials not available");
        }
    }

    private static boolean authenticationFailure(Exception failure) {
        return failure instanceof MissingCredentials
                || CloudSlideListing.authenticationFailure(failure);
    }

    private void addSlides() {
        var chosen = slides.stream().filter(slide -> selected.contains(slide.key())).toList();
        if (chosen.isEmpty()) {
            Dialogs.showWarningNotification("HistoForge", "No slides selected.");
            return;
        }
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("HistoForge", "Please create a project first.");
            return;
        }
        long request = viewGeneration;
        String bucket = connection.bucket();
        add.setDisable(true);
        var imports =
                chosen.stream()
                        .map(slide -> new CloudProjectImporter.Slide(slide.key(), slide.filename()))
                        .toList();
        Thread.startVirtualThread(
                () -> {
                    var result = CloudProjectImporter.add(project, bucket, imports);
                    result.failures()
                            .forEach(
                                    failure ->
                                            logger.warn(
                                                    "Could not add {}",
                                                    failure.slide().filename(),
                                                    failure.cause()));
                    result.persistenceFailure()
                            .ifPresent(
                                    failure ->
                                            logger.warn(
                                                    "Could not save the project after adding cloud slides",
                                                    failure));
                    Platform.runLater(
                            () -> {
                                if (isCurrentView(request)) {
                                    add.setDisable(false);
                                    result.added().forEach(slide -> selected.remove(slide.key()));
                                    refreshProjectMembership();
                                    applyFilter(page);
                                }
                                // Import completion is independent of window visibility; a save
                                // failure must be reported.
                                if (!result.added().isEmpty()) {
                                    if (qupath.getProject() == project) qupath.refreshProject();
                                    if (result.persistenceFailure().isPresent())
                                        Dialogs.showErrorMessage(
                                                "Project Save Error",
                                                result.added().size()
                                                        + " slide(s) were added to the current project session, but QuPath did not complete the project save. Use Save Project & Refresh to retry: "
                                                        + result.persistenceFailure()
                                                                .orElseThrow()
                                                                .getMessage());
                                    else
                                        Dialogs.showInfoNotification(
                                                "HistoForge",
                                                result.added().size()
                                                        + " slide(s) added to project.");
                                }
                                if (!result.failures().isEmpty())
                                    Dialogs.showWarningNotification(
                                            "HistoForge",
                                            result.failures().size()
                                                    + " slide(s) could not be added or named completely.");
                            });
                });
    }

    private boolean saveBeforeOpening() {
        var current = qupath.getViewer().getImageData();
        if (current == null || !current.isChanged()) return true;
        var choice =
                Dialogs.builder()
                        .title("Save changes")
                        .owner(qupath.getStage())
                        .contentText(
                                "Save changes to "
                                        + ServerTools.getDisplayableImageName(current.getServer())
                                        + "?")
                        .buttons(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL)
                        .showAndWait()
                        .orElse(ButtonType.CANCEL);
        if (qupath.getViewer().getImageData() != current) {
            Dialogs.showErrorMessage(
                    "Open Cloud Slide",
                    "The active image changed while the save prompt was open. Open the cloud slide again when ready.");
            return false;
        }
        if (choice == ButtonType.NO) return true;
        if (choice != ButtonType.YES) return false;
        try {
            var project = qupath.getProject();
            var entry = project == null ? null : project.getEntry(current);
            if (entry == null)
                throw new IOException(
                        "Add the current image to a project and save it before opening another slide.");
            images.saveCurrentViewerImage(entry, current);
            project.syncChanges();
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.warn("Could not save the current image before opening a cloud slide", failure);
            Dialogs.showErrorMessage("Save Error", "Failed to save: " + failure.getMessage());
            return false;
        }
    }

    private void setImageType(ImageData<BufferedImage> image) {
        if (image.getImageType() != null && image.getImageType() != ImageData.ImageType.UNSET)
            return;
        var setting = PathPrefs.imageTypeSettingProperty().get();
        if (setting != PathPrefs.ImageTypeSetting.AUTO_ESTIMATE
                && setting != PathPrefs.ImageTypeSetting.PROMPT) return;
        var thumbnail = qupath.getImageRegionStore().getThumbnail(image.getServer(), 0, 0, true);
        var estimated = GuiTools.estimateImageType(image.getServer(), thumbnail);
        if (setting == PathPrefs.ImageTypeSetting.PROMPT)
            ImageDetailsPane.promptToSetImageType(image, estimated);
        else {
            image.setImageType(estimated);
            image.setChanged(false);
        }
    }

    private void refreshProjectMembership() {
        inProject.clear();
        if (qupath.getProject() == null) return;
        Set<String> uris = new HashSet<>();
        for (var entry : qupath.getProject().getImageList()) {
            try {
                entry.getURIs().forEach(uri -> uris.add(uri.toString()));
            } catch (IOException failure) {
                logger.debug("Could not read slide URI: {}", failure.getMessage());
            }
        }
        for (Slide slide : slides) {
            try {
                if (uris.contains(slide.uri(connection.bucket()).toString()))
                    inProject.add(slide.key());
            } catch (java.net.URISyntaxException failure) {
                logger.debug("Invalid cloud slide URI", failure);
            }
        }
    }

    private void loadFailure(Exception failure) {
        logger.warn("Could not list cloud slides", failure);
        if (authenticationFailure(failure)) expired(failure);
        else list.setPlaceholder(new Label("Error: " + failure.getMessage()));
    }

    private void expired(Exception failure) {
        // No cached credentials remain to invalidate; another window may be signing in.
        if (!(failure instanceof MissingCredentials)) credentials.invalidate();
        Dialogs.showWarningNotification(
                "Session Expired", "AWS session expired — please sign in again.");
        setup(null);
    }

    private static void close(ImageData<?> image) {
        try {
            image.close();
        } catch (Exception failure) {
            logger.warn("Cloud image cleanup failed", failure);
        }
    }

    private static TextField field(String value, String hint) {
        var field = new TextField(value == null ? "" : value);
        field.setPromptText(hint);
        return field;
    }

    private static Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox row(javafx.scene.Node... nodes) {
        var row = new HBox(10, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private final class SlideCell extends ListCell<Slide> {
        private final CheckBox checkbox = new CheckBox();
        private final ImageView image = new ImageView();
        private final ProgressIndicator progress = new ProgressIndicator();
        private final Label name = new Label(), detail = new Label();
        private final HBox cell;

        SlideCell() {
            image.setFitWidth(80);
            image.setFitHeight(60);
            image.setPreserveRatio(true);
            progress.setMaxSize(24, 24);
            var thumbnail = new StackPane(progress, image);
            thumbnail.setMinSize(80, 60);
            thumbnail.setMaxSize(80, 60);
            thumbnail.setPrefSize(80, 60);
            thumbnail.setStyle("-fx-background-color: #1a1a1a;");
            name.setStyle("-fx-font-weight: bold;");
            detail.getStyleClass().add(HistoforgeStyles.MUTED);
            detail.setStyle("-fx-font-size: 11;");
            cell = row(checkbox, thumbnail, new VBox(2, name, detail));
            checkbox.setOnAction(
                    event -> {
                        var slide = getItem();
                        if (slide == null) return;
                        if (checkbox.isSelected()) selected.add(slide.key());
                        else selected.remove(slide.key());
                        if (CloudSlideListing.SELECTED.equals(filter.getValue())) applyFilter(0);
                    });
        }

        @Override
        protected void updateItem(Slide slide, boolean empty) {
            super.updateItem(slide, empty);
            setText(null);
            if (empty || slide == null) {
                image.setImage(null);
                setGraphic(null);
                return;
            }
            checkbox.setSelected(selected.contains(slide.key()));
            name.setText(slide.filename());
            detail.setText(
                    CloudSlideListing.sizeLabel(slide.size())
                            + "  •  "
                            + DATE.format(slide.modified()));
            image.setImage(thumbnails.get(slide));
            progress.setVisible(image.getImage() == null);
            setGraphic(cell);
        }
    }
}
