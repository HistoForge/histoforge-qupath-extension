package qupath.ext.histoforge.ui.jobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;

/**
 * In-memory view of the gateway's jobs (the server owns state; the plugin holds a view), kept fresh
 * by serial refreshes — the jobs counterpart of {@link qupath.ext.histoforge.ui.ModulesRegistry}.
 * Consumers read {@link #jobs()}: the Jobs tab binds its table to it, the shell derives the header
 * {@code ⤓ N} badge from it. Every job seen reaching a terminal state is handed to {@code
 * onFinished} exactly once, so the shell can react (log a failure, refresh modules) without
 * re-implementing change detection.
 *
 * <p>Refresh requests and observable state belong to the FX thread. Network IO runs on a virtual
 * thread, one fetch at a time. After a successful idle snapshot there is no timer; opening Jobs or
 * submitting work requests a fresh snapshot. Known active cloud work (including result
 * finalization) schedules another fetch two seconds after completion, even while the tab is hidden.
 * An idle connection failure does not keep an otherwise unused backend awake.
 */
public final class JobsPoller {

    private static final Logger logger = LoggerFactory.getLogger(JobsPoller.class);

    /** Shared Jobs polling cadence; no inference or worker lifecycle consumes it. */
    private static final long POLL_INTERVAL_MS = 2000;

    private final Supplier<ModuleGatewayClient> client;
    private final Supplier<GatewayTransport> transport;
    private final Consumer<JobRow> onFinished;
    private final Runnable onBaseline;
    private final ObservableList<JobRow> jobs = FXCollections.observableArrayList();
    private List<JobRow> serverRows = List.of();
    private List<JobRow.Inference> inferenceRows = List.of();
    private final Set<String> announced = new HashSet<>();
    private final ReadOnlyBooleanWrapper loaded = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper failed = new ReadOnlyBooleanWrapper();
    private final PauseTransition nextPoll = new PauseTransition(Duration.millis(POLL_INTERVAL_MS));
    private boolean started;
    private boolean refreshPending;
    private volatile long mutationGeneration;

    public JobsPoller(
            Supplier<ModuleGatewayClient> client,
            Supplier<GatewayTransport> transport,
            Consumer<JobRow> onFinished,
            Runnable onBaseline) {
        this.client = client;
        this.transport = transport;
        this.onFinished = onFinished;
        this.onBaseline = onBaseline;
        nextPoll.setOnFinished(event -> refresh());
    }

    /** The live list — table binding + derived counts. Mutated on the FX thread only. */
    public ObservableList<JobRow> jobs() {
        return jobs;
    }

    /** Inference has an existing project execution owner; observe it without a second poller. */
    public void observeInference(ObservableList<JobRow.Inference> source) {
        Runnable update =
                () -> {
                    inferenceRows = List.copyOf(source);
                    publishRows();
                };
        source.addListener((ListChangeListener<JobRow.Inference>) change -> update.run());
        update.run();
    }

    private void publishRows() {
        var combined = new ArrayList<JobRow>(serverRows);
        combined.addAll(inferenceRows);
        combined.sort(
                Comparator.comparing(JobRow::updatedAt).reversed().thenComparing(JobRow::key));
        if (!jobs.equals(combined)) jobs.setAll(combined);
    }

    /** Enable fetching after a successful connection probe; reconnects also request fresh state. */
    public void start() {
        started = true;
        refresh();
    }

    /** Whether one successful server page has established the initial observation boundary. */
    public boolean hasBaseline() {
        return loaded.get();
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

    /** Drop a successfully cleared row and invalidate an older response still in flight. */
    void forget(JobRow row) {
        mutationGeneration++;
        serverRows =
                serverRows.stream().filter(current -> !current.key().equals(row.key())).toList();
        publishRows();
    }

    /** Request on FX. A refresh received during loading must survive an older idle response. */
    public void refresh() {
        if (!started) return;
        nextPoll.stop();
        if (loading.get()) {
            refreshPending = true;
            return;
        }
        loading.set(true);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Runnable update = fetchUpdate();
                                Platform.runLater(
                                        () -> {
                                            update.run();
                                            finishRefresh();
                                        });
                            } catch (Exception e) {
                                logger.debug("jobs poll failed; keeping previous list", e);
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
        } else if (serverRows.stream().anyMatch(JobRow::isRunning)) {
            nextPoll.playFromStart();
        }
    }

    /** Capture off-thread, then apply on FX only if no successful clear overtook the response. */
    Runnable fetchUpdate() throws Exception {
        long generation = mutationGeneration;
        List<JobRow> rows = fetch();
        return () -> {
            if (generation == mutationGeneration) {
                apply(rows);
                failed.set(false);
            } else {
                refreshPending = true;
            }
        };
    }

    /** Publish no partial snapshot if either route fails; the last complete view stays visible. */
    List<JobRow> fetch() throws Exception {
        ModuleGatewayClient connected = client.get();
        var training = new TrainingRunGatewayClient(transport.get(), connected.capabilities());
        var rows = new ArrayList<JobRow>();
        connected.listJobs().forEach(job -> rows.add(new JobRow.Admin(job)));
        training.listRuns().forEach(run -> rows.add(new JobRow.Training(run)));
        rows.sort(Comparator.comparing(JobRow::updatedAt).reversed().thenComparing(JobRow::key));
        return List.copyOf(rows);
    }

    /** Apply a fresh list on the FX thread: rows, then one callback per newly-finished job. */
    void apply(List<JobRow> list) {
        // Only replace the rows when something actually changed: an unconditional setAll every
        // tick re-renders every cell, which tears down an open tooltip and flickers the table.
        // Statuses are records, so this is a value comparison of the whole list.
        serverRows = List.copyOf(list);
        publishRows();
        // The first tick adopts whatever is already terminal *silently*: those jobs finished before
        // we started watching, so announcing them would re-report stale failures on every restart.
        // Ids are recorded either way, so each job is announced at most once.
        boolean announce = loaded.get();
        if (!loaded.get()) {
            loaded.set(true);
            onBaseline.run();
        }
        for (JobRow job : list) {
            if (!job.isTerminal() || !announced.add(job.key())) {
                continue;
            }
            if (announce) {
                onFinished.accept(job);
            }
        }
    }
}
