package qupath.ext.histoforge.ui.training;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.Platform;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunAccepted;
import qupath.ext.histoforge.util.Errors;

/** One non-cancellable submission call after preparation. The backend owns run state. */
final class TrainingRunSubmission {
    private final Consumer<Boolean> runningChanged;
    private final Consumer<Runnable> uiDispatcher;
    private boolean running;
    private CompletableFuture<Void> settled = CompletableFuture.completedFuture(null);

    TrainingRunSubmission(Consumer<Boolean> runningChanged) {
        this(runningChanged, Platform::runLater);
    }

    TrainingRunSubmission(Consumer<Boolean> runningChanged, Consumer<Runnable> uiDispatcher) {
        this.runningChanged = runningChanged;
        this.uiDispatcher = uiDispatcher;
    }

    boolean isRunning() {
        return running;
    }

    /** Let an in-flight POST settle; the update helper then cancels accepted cloud runs. */
    CompletableFuture<Void> awaitSubmission() {
        return settled;
    }

    /** Validate before send, freeze the call inputs, and never retry an unconfirmed submission. */
    void start(
            TrainingRunGatewayClient client,
            TrainingRunRequest request,
            Consumer<TrainingRunAccepted> accepted,
            Consumer<Throwable> failed) {
        if (running) return;
        TrainingRunRequestCodec.toJson(request, client.limits());
        settled = new CompletableFuture<>();
        running = true;
        runningChanged.accept(true);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                var result = client.submit(request);
                                uiDispatcher.accept(
                                        () -> {
                                            finish();
                                            accepted.accept(result);
                                        });
                            } catch (Exception error) {
                                uiDispatcher.accept(
                                        () -> {
                                            finish();
                                            failed.accept(error);
                                        });
                            }
                        });
    }

    /** Only these failures prove that this submission did not reach run acceptance. */
    static boolean definitelyRejected(Throwable error) {
        if (!(error instanceof InferenceException response)) return false;
        return response.gatewayError()
                .map(
                        value ->
                                switch (value.code()) {
                                    case RATE_LIMITED,
                                            MODULE_NOT_FOUND,
                                            MODULE_IDENTITY_MISMATCH,
                                            TRAINING_DATASET_NOT_FOUND ->
                                            true;
                                    // These four codes occur after accepted-request replay lookup.
                                    // The transport may refresh a 401 and replay exact bytes once.
                                    // Body validation, auth, or deployment changes can then fail
                                    // after the earlier attempt accepted the run.
                                    default -> false;
                                })
                .orElse(false);
    }

    /** Render the safe gateway diagnostic first, followed by the no-replay instruction. */
    static String unconfirmedFailureMessage(Throwable error) {
        String detail =
                error instanceof InferenceException response
                        ? response.gatewayError()
                                .map(GatewayErrorCodec.Error::message)
                                .orElseGet(() -> Errors.message(error))
                        : Errors.message(error);
        return detail
                + "\n\nThe training request may already have been accepted. "
                + "Check Jobs before starting another run.";
    }

    private void finish() {
        CompletableFuture<Void> completed = settled;
        running = false;
        runningChanged.accept(false);
        completed.complete(null);
    }
}
