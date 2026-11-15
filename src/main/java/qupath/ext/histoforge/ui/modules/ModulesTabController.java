package qupath.ext.histoforge.ui.modules;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.training.TrainingReportViewer;
import qupath.ext.histoforge.util.BrowserLauncher;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Browse the shared installed catalog and inspect one module at a time. Selection survives catalog
 * replacement by module ref; details always use the newly captured descriptor. Opening a module
 * does not fetch documentation, execute scripts or contact Hugging Face.
 *
 * <p>Installation, publication, deletion and training reports retain their existing controllers.
 */
public class ModulesTabController {

    private static final Logger logger = LoggerFactory.getLogger(ModulesTabController.class);

    @FXML private TextField searchField;
    @FXML private ListView<InstalledModule> moduleList;
    @FXML private BorderPane moduleDetail;
    @FXML private Label emptyStateLabel;
    @FXML private Button installButton;
    @FXML private HBox loadStatus;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadStatusLabel;
    @FXML private Button retryButton;

    private QuPathGUI qupath;
    private ModuleGatewayClient client;
    private ModulesRegistry registry;
    private Runnable refreshJobs;
    private boolean updatingList;
    private String removingModuleRef;

    /** Wire dependencies after FXML loading (called by MainController). */
    public void init(
            QuPathGUI qupath,
            ModuleGatewayClient client,
            ModulesRegistry registry,
            Runnable openJobs,
            Runnable refreshJobs) {
        this.qupath = qupath;
        this.client = client;
        this.registry = registry;
        this.refreshJobs = refreshJobs;
        installButton.setOnAction(e -> InstallDialog.show(qupath, client, openJobs));
        retryButton.setOnAction(e -> registry.refresh());
        SplitPane.setResizableWithParent(moduleList, false);
        moduleList.setCellFactory(ignored -> new ModuleCell());
        moduleList
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (obs, old, module) -> {
                            if (!updatingList) showDetails(module);
                        });
        searchField.textProperty().addListener((obs, old, text) -> render());
        registry.asObservable().addListener((ListChangeListener<InstalledModule>) c -> render());
        registry.loadingProperty().addListener(obs -> updateLoadStatus());
        registry.loadedProperty().addListener(obs -> updateLoadStatus());
        registry.failedProperty().addListener(obs -> updateLoadStatus());
        render();
        updateLoadStatus();
    }

    /** Reveal the exact result selected in Jobs, including when the query is already unchanged. */
    public void showModule(String ref) {
        searchField.setText(ref);
        moduleList.getItems().stream()
                .filter(module -> module.ref().equals(ref))
                .findFirst()
                .ifPresent(
                        module -> {
                            moduleList.getSelectionModel().select(module);
                            moduleList.scrollTo(module);
                        });
    }

    // ── Rendering ───────────────────────────────────────────────────

    private void render() {
        InstalledModule selected = moduleList.getSelectionModel().getSelectedItem();
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        List<InstalledModule> modules =
                registry.asObservable().stream()
                        .filter(m -> matches(m, query.toLowerCase(Locale.ROOT)))
                        .sorted(Comparator.comparing(InstalledModule::ref))
                        .toList();

        // setAll can emit intermediate selection changes. Render only the final selected snapshot.
        updatingList = true;
        try {
            moduleList.getItems().setAll(modules);
            InstalledModule next =
                    modules.stream()
                            .filter(
                                    module ->
                                            selected != null && module.ref().equals(selected.ref()))
                            .findFirst()
                            .orElse(modules.isEmpty() ? null : modules.getFirst());
            moduleList.getSelectionModel().select(next);
        } finally {
            updatingList = false;
        }
        updateEmptyMessage();
        showDetails(moduleList.getSelectionModel().getSelectedItem());
    }

    private void showDetails(InstalledModule module) {
        if (module == null) {
            moduleDetail.setCenter(ModuleControls.muted("No module selected"));
            return;
        }
        var details =
                new ModuleDetailsView(
                        module,
                        this::publish,
                        this::viewOnHf,
                        canViewOnHuggingFace(module.ref()),
                        this::viewTrainingReport,
                        this::delete);
        details.setRemovalPending(removingModuleRef != null);
        moduleDetail.setCenter(details);
    }

    /** Loading changes update only the status controls, never rebuild selected module details. */
    private void updateLoadStatus() {
        boolean loading = registry.loadingProperty().get();
        boolean failed = registry.failedProperty().get();
        boolean removing = removingModuleRef != null;
        loadStatus.setVisible(removing || loading || failed);
        loadStatus.setManaged(loadStatus.isVisible());
        loadStatusLabel.setText(
                removing
                        ? "Removing " + removingModuleRef + "…"
                        : failed
                                ? "Couldn’t load modules."
                                : registry.loadedProperty().get()
                                        ? "Refreshing modules…"
                                        : "Loading modules…");
        loadingIndicator.setVisible(removing || (loading && !failed));
        loadingIndicator.setManaged(loadingIndicator.isVisible());
        retryButton.setVisible(!removing && failed && !loading);
        retryButton.setManaged(retryButton.isVisible());
        updateEmptyMessage();
    }

    private void updateEmptyMessage() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        emptyStateLabel.setText(
                registry.loadedProperty().get()
                        ? registry.asObservable().isEmpty()
                                ? "No modules installed."
                                : "No modules match \"" + query + "\"."
                        : registry.loadingProperty().get() || registry.failedProperty().get()
                                ? ""
                                : "Connect to load modules.");
    }

    private static boolean matches(InstalledModule module, String queryLower) {
        if (queryLower.isEmpty()) {
            return true;
        }
        return contains(module.ref(), queryLower)
                || contains(module.name(), queryLower)
                || contains(module.description(), queryLower);
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    // ── Actions ─────────────────────────────────────────────────────

    private void viewOnHf(InstalledModule module) {
        BrowserLauncher.open(
                "https://huggingface.co/"
                        + module.ref()
                        + module.descriptor()
                                .identity()
                                .resolvedCommitSha()
                                .map(sha -> "/tree/" + sha)
                                .orElse(""));
    }

    private void publish(InstalledModule module) {
        PublishDialog.show(qupath, client, module, refreshJobs);
    }

    private void viewTrainingReport(InstalledModule module) {
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                TrainingReportViewer.open(client.moduleTrainingReport(module));
                            } catch (Exception e) {
                                logger.warn("open training report for {} failed", module.ref(), e);
                                Platform.runLater(
                                        () ->
                                                Dialogs.showErrorMessage(
                                                        "Training report unavailable",
                                                        module.ref() + ": " + Errors.message(e)));
                            }
                        });
    }

    private void delete(InstalledModule module) {
        if (removingModuleRef != null) return;
        boolean confirm =
                Dialogs.showYesNoDialog("Remove module", deleteConfirmation(module.ref()));
        if (!confirm) {
            return;
        }
        setRemovingModule(module.ref());
        try {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    client.deleteModule(module.ref());
                                    Platform.runLater(
                                            () -> {
                                                setRemovingModule(null);
                                                Dialogs.showInfoNotification(
                                                        "Module removed",
                                                        module.ref() + " was removed.");
                                                registry.refresh();
                                            });
                                } catch (Exception e) {
                                    Platform.runLater(() -> removalFailed(module, e));
                                }
                            });
        } catch (RuntimeException e) {
            removalFailed(module, e);
        }
    }

    private void setRemovingModule(String ref) {
        removingModuleRef = ref;
        if (moduleDetail.getCenter() instanceof ModuleDetailsView details) {
            details.setRemovalPending(ref != null);
        }
        updateLoadStatus();
    }

    private void removalFailed(InstalledModule module, Exception error) {
        logger.warn("remove {} failed", module.ref(), error);
        setRemovingModule(null);
        Dialogs.showErrorMessage(
                "Module removal not confirmed", module.ref() + ": " + Errors.message(error));
    }

    static boolean canViewOnHuggingFace(String moduleRef) {
        return ModuleRef.parse(moduleRef)
                .map(ref -> ModuleContract.workspaceTrainingRunId(ref).isEmpty())
                .orElse(false);
    }

    static String deleteConfirmation(String moduleRef) {
        if (!canViewOnHuggingFace(moduleRef)) {
            return "Remove "
                    + moduleRef
                    + " from your cloud account? This removes the trained module and its weights."
                    + " If you have not published it, this may be the only copy. Uploaded training"
                    + " data and Jobs history are not deleted.";
        }
        return "Remove "
                + moduleRef
                + " from your cloud account? You can reinstall this module from Hugging Face.";
    }

    private static final class ModuleCell extends ListCell<InstalledModule> {
        private final Label name = ModuleControls.heading("");
        private final Label ref = ModuleControls.label("");
        private final VBox body = new VBox(5, name, ref);

        ModuleCell() {
            body.setPadding(new Insets(11, 8, 11, 8));
            body.setMinWidth(0);
            ref.getStyleClass().add("histoforge-module-ref");
            name.setWrapText(false);
            ref.setWrapText(false);
            body.maxWidthProperty().bind(widthProperty().subtract(16));
        }

        @Override
        protected void updateItem(InstalledModule module, boolean empty) {
            super.updateItem(module, empty);
            setText(null);
            setGraphic(empty || module == null ? null : body);
            setTooltip(empty || module == null ? null : new Tooltip(module.ref()));
            if (empty || module == null) return;
            name.setText(module.name());
            ref.setText(module.ref());
        }
    }
}
