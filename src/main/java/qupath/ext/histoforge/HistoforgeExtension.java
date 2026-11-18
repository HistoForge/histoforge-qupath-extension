package qupath.ext.histoforge;

import java.io.IOException;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.cloud.AwsCredentialManager;
import qupath.ext.histoforge.cloud.CloudSlideDialog;
import qupath.ext.histoforge.cloud.S3CredentialStore;
import qupath.ext.histoforge.roi.HierarchyListener;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.roi.RoiToolHandler;
import qupath.ext.histoforge.roi.ToolIcons;
import qupath.ext.histoforge.roi.ViewerContextMenuHandler;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.app.MainController;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.tools.PathTool;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Installs HistoForge's window, cloud-slide action and administrative ROI tool in QuPath. */
public class HistoforgeExtension implements QuPathExtension {
    private static final Logger logger = LoggerFactory.getLogger(HistoforgeExtension.class);
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private boolean isInstalled = false;

    private QuPathGUI qupath;
    private RoiManager roiManager;
    private HierarchyListener hierarchyListener;
    private PathTool histoforgeRoiTool;

    // Reuse one window; creation waits until the user opens HistoForge.
    private Stage histoforgeWindow;

    private ChangeListener<ImageData<?>> imageDataListener;

    // The listener must leave this hierarchy before following a different image.
    private PathObjectHierarchy currentHierarchy;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) return;
        isInstalled = true;
        this.qupath = qupath;
        roiManager = new RoiManager();
        hierarchyListener = new HierarchyListener(roiManager, qupath::getImageData);
        histoforgeRoiTool =
                PathTools.createTool(
                        new RoiToolHandler(qupath, roiManager),
                        "HistoForge ROI",
                        ToolIcons.createRoiToolIcon());
        qupath.getToolManager().installTool(histoforgeRoiTool, null);
        new ViewerContextMenuHandler(qupath, roiManager);
        imageDataListener = (property, previous, current) -> observeImage(current);
        qupath.imageDataProperty().addListener(imageDataListener);
        observeImage(qupath.getImageData());
        var credentials = new AwsCredentialManager(new S3CredentialStore());
        AwsCredentialManager.setInstance(credentials);
        credentials
                .restoreSavedSession()
                .exceptionally(
                        failure -> {
                            logger.warn("Could not restore the saved AWS session", failure);
                            return false;
                        });
        var open = new MenuItem("Open HistoForge");
        open.setOnAction(event -> showWindow());
        var cloud = new MenuItem("Open from Cloud...");
        cloud.setOnAction(event -> CloudSlideDialog.show(qupath, credentials));
        qupath.getMenu("Extensions>HistoForge", true).getItems().addAll(open, cloud);
    }

    /** Creates the window on demand, retaining it for subsequent openings. */
    public void showWindow() {
        if (histoforgeWindow == null) {
            try {
                buildWindow();
            } catch (IOException e) {
                Dialogs.showErrorMessage(
                        "HistoForge Error", "Failed to load HistoForge window: " + e.getMessage());
                logger.error("Failed to create HistoForge window", e);
                return;
            }
        }
        histoforgeWindow.show();
        histoforgeWindow.toFront();
    }

    private void buildWindow() throws IOException {
        var loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
        loader.setClassLoader(MainController.class.getClassLoader());
        Parent root = loader.load();
        MainController controller = loader.getController();
        var window = new Stage();
        window.initOwner(qupath.getStage());
        window.setTitle("HistoForge");
        controller.init(qupath, hierarchyListener, window);
        var scene = new Scene(root);
        HistoforgeStyles.apply(scene);
        window.setScene(scene);
        window.setMinWidth(300);
        window.setMinHeight(500);
        window.setHeight(750);
        histoforgeWindow = window;
    }

    /** The lookup and listener belong to the active image, never the previous viewer contents. */
    private void observeImage(ImageData<?> image) {
        if (currentHierarchy != null) currentHierarchy.removeListener(hierarchyListener);
        currentHierarchy = image == null ? null : image.getHierarchy();
        roiManager.populateFromHierarchy(currentHierarchy);
        if (currentHierarchy != null) currentHierarchy.addListener(hierarchyListener);
    }

    public PathTool getHistoforgeRoiTool() {
        return histoforgeRoiTool;
    }

    public RoiManager getRoiManager() {
        return roiManager;
    }

    public HierarchyListener getHierarchyListener() {
        return hierarchyListener;
    }

    @Override
    public String getName() {
        return "HistoForge";
    }

    @Override
    public String getDescription() {
        return "ROI export and analysis";
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
