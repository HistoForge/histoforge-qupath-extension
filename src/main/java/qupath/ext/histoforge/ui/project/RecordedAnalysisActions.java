package qupath.ext.histoforge.ui.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Code;
import qupath.ext.histoforge.large.LargeActionHandleStore;

/** Explicit stop/clear IO for recorded targets, after the caller releases their local workers. */
final class RecordedAnalysisActions {
    private static final Logger logger = LoggerFactory.getLogger(RecordedAnalysisActions.class);

    private RecordedAnalysisActions() {}

    /** An absent image ID stops the live batch; after reopening, only one image is selected. */
    static List<String> stop(
            LargeActionHandleStore handles,
            String actionId,
            Optional<String> imageId,
            Supplier<GatewayTransport> transport,
            Supplier<ModuleGatewayClient> client)
            throws Exception {
        var records = handles.scanRecords();
        records.requireMutableAction(actionId, imageId);
        var targets =
                records.currentHandles().stream()
                        .filter(
                                h ->
                                        h.actionId().equals(actionId)
                                                && imageId.map(h.imageId()::equals).orElse(true))
                        .filter(h -> !h.phase().hasCompletedSave())
                        .toList();
        // Record local cancellation before network requests, preventing later result saves
        // even if the first DELETE fails.
        for (var target : targets)
            if (!target.phase().terminal())
                handles.abandon(target.actionId(), target.handleGenerationId());
        DurableModelRunExecutor remote = null;
        List<String> stopped = new ArrayList<>();
        for (var target : targets) {
            for (var step : target.steps())
                for (var run : step.runs()) {
                    // Recorded only after validating an immutable COMPLETE manifest. Lost local
                    // bytes do not make that exact cloud run active again.
                    if (run.completeResultFiles().isPresent()) {
                        stopped.add(run.modelRunId());
                        continue;
                    }
                    var connection = transport.get();
                    if (!connection.matchesCurrentDeployment(target.module().deploymentId()))
                        throw new IOException(
                                "Reconnect to this analysis's original cloud backend to stop its work.");
                    if (remote == null)
                        remote =
                                new DurableModelRunExecutor(
                                        connection, client.get().capabilities());
                    try {
                        remote.cancel(run.modelRunId());
                    } catch (InferenceException error) {
                        if (error.gatewayError()
                                .filter(
                                        e ->
                                                e.code() == Code.MODEL_RUN_NOT_FOUND
                                                        || e.code() == Code.MODEL_RUN_EXPIRED)
                                .isEmpty()) throw error;
                    }
                    stopped.add(run.modelRunId());
                }
        }
        try {
            handles.cleanupStoppedFiles(
                    targets.stream().map(h -> h.handleGenerationId()).collect(Collectors.toSet()));
        } catch (IOException cleanupFailure) {
            // A locked local file cannot undo an acknowledged cloud stop. Keep its history so
            // explicit Clear history can retry deletion after the file becomes available.
            logger.warn(
                    "Cloud work stopped, but its recovery files could not be removed",
                    cleanupFailure);
        }
        return List.copyOf(stopped);
    }

    /** The caller has verified terminal cloud state; this never deletes saved QuPath objects. */
    static void clear(LargeActionHandleStore handles, String actionId, String imageId)
            throws IOException {
        var records = handles.scanRecords();
        records.requireMutableAction(actionId, Optional.of(imageId));
        var targets =
                records.currentHandles().stream()
                        .filter(h -> h.actionId().equals(actionId) && h.imageId().equals(imageId))
                        .toList();
        if (targets.stream().anyMatch(h -> !h.phase().terminal()))
            throw new IllegalStateException(
                    "This analysis still has unfinished work. Refresh Jobs.");
        for (var target : targets) handles.dismiss(target.actionId(), target.handleGenerationId());
    }
}
