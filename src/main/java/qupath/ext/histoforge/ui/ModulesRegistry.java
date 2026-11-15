package qupath.ext.histoforge.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.modules.InstalledModule;

/**
 * In-memory, plugin-side view of the modules installed on the gateway (the server owns state; the
 * plugin holds a view). Each {@link InstalledModule} retains the exact descriptor and authenticated
 * gateway capabilities captured with it. The registry is populated from {@code GET /v1/modules} by
 * {@link #refresh()} and never persisted: a restart starts empty and re-fetches.
 *
 * <p>{@code MainController} owns one instance and shares it across the tabs, so the installed set
 * is refreshed on connect and after job reconciliation, not per tab or project refresh. Consumers
 * read a snapshot ({@link #get}, {@link #asObservable}) or observe the list for changes — the
 * Project tab re-renders on change, so a completed install shows up without a manual reload.
 */
public class ModulesRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModulesRegistry.class);

    private final ObservableList<InstalledModule> modules = FXCollections.observableArrayList();
    private final Supplier<ModuleGatewayClient> client;
    private final ReadOnlyBooleanWrapper loaded = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper failed = new ReadOnlyBooleanWrapper();
    private boolean refreshPending;

    public ModulesRegistry(Supplier<ModuleGatewayClient> client) {
        this.client = client;
    }

    public ModulesRegistry(ModuleGatewayClient client) {
        this(() -> client);
    }

    /**
     * Request a catalog refresh on FX; network IO runs off-thread. Keep the previous list while
     * loading or after failure. Requests received during loading coalesce into one follow-up, so a
     * job that finishes during an older catalog fetch is not lost.
     */
    public void refresh() {
        if (loading.get()) {
            refreshPending = true;
            return;
        }
        loading.set(true);
        failed.set(false);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                List<InstalledModule> fetched = client.get().listInstalled();
                                Platform.runLater(
                                        () -> {
                                            modules.setAll(fetched);
                                            loaded.set(true);
                                            finishRefresh();
                                        });
                            } catch (Exception e) {
                                logger.warn("modules refresh failed; keeping previous list", e);
                                Platform.runLater(
                                        () -> {
                                            failed.set(true);
                                            finishRefresh();
                                        });
                            }
                        });
    }

    private void finishRefresh() {
        loading.set(false);
        if (refreshPending) {
            refreshPending = false;
            refresh();
        }
    }

    public ReadOnlyBooleanProperty loadedProperty() {
        return loaded.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty loadingProperty() {
        return loading.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty failedProperty() {
        return failed.getReadOnlyProperty();
    }

    /** The installed module for a qualified {@code <owner>/<name>} ref, if present. */
    public Optional<InstalledModule> get(String ref) {
        return modules.stream().filter(m -> Objects.equals(m.ref(), ref)).findFirst();
    }

    /** The live list, for reactive rendering (Modules tab) and change observation (Project tab). */
    public ObservableList<InstalledModule> asObservable() {
        return modules;
    }
}
