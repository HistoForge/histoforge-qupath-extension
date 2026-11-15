package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor.PreparedSourceCorruptException;
import qupath.ext.histoforge.client.ModelRunHandle;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModelExecutionPlanCodec;
import qupath.ext.histoforge.contract.ModelRunSourceCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.large.RunImageSources;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;

/**
 * Supplies the accepted run's images on the existing action thread. Previously bound units can
 * execute in the cloud while the next image is uploaded. Every upload and binding is recorded
 * through the recipe's single journal; this class neither schedules GPU work nor saves predictions.
 */
final class RunImageDelivery implements DurableRecipeDispatcher.ImageDelivery {
    private final RecoverableImageUploads uploads;

    RunImageDelivery(RecoverableImageUploads uploads) {
        this.uploads = Objects.requireNonNull(uploads);
    }

    @Override
    public void deliver(
            DurableStepJournal journal,
            int shardIndex,
            DurableModelRunExecutor runs,
            ModelRunHandle run,
            BooleanSupplier stop)
            throws IOException, InterruptedException, InferenceException {
        String step = run.request().producer().stepId();
        if (!journal.run(step, shardIndex).modelRunId().equals(run.request().modelRunId()))
            throw new IllegalArgumentException("Image delivery requires the journal's current run");
        try (uploads) {
            RecoverableImageUploads.requireRunning(stop);
            var delivery = runs.readSources(run);
            restoreAccepted(journal, shardIndex, run, delivery);
            journal.handles().discardBoundSourceImages(journal.currentAction());
            if (!delivery.active()) return;
            var priorFailure =
                    journal.run(step, shardIndex).imageSources().orElseThrow().failureReport();
            if (priorFailure.isPresent()) {
                runs.reportSourceFailure(run, priorFailure.orElseThrow());
                return;
            }
            var model = journal.currentAction().handle().module().activatedModels().get(step);
            try {
                for (var input : run.request().inputs()) {
                    var view = (SourceCaptureCodec.View) input.rasterInput().sourceView();
                    var plan = delivery.executionPlans().get(input.inputId());
                    for (int unit = 0; unit < ModelExecutionPlanCodec.unitCount(plan); unit++) {
                        RecoverableImageUploads.requireRunning(stop);
                        if (ModelRunSourceCodec.unitBinding(
                                        delivery.sourceBindings(), input.inputId(), unit)
                                .isPresent()) continue;
                        var rectangle =
                                ModelRunSourceCodec.processingRectangle(
                                        input.rasterInput(), plan, unit);
                        var required =
                                SourceViewGeometry.requiredCapturedSourcePixelRect(
                                                view,
                                                input.rasterInput().frame().transform(),
                                                rectangle)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "Processing image has no real source pixels"));
                        var upload =
                                uploads.provide(
                                        journal,
                                        shardIndex,
                                        step,
                                        input.inputId(),
                                        unit,
                                        view.capture(),
                                        required,
                                        model,
                                        stop);
                        RecoverableImageUploads.requireRunning(stop);
                        try {
                            bind(journal, runs, run, view.capture(), upload);
                        } catch (InferenceException unavailable) {
                            if (upload.bindingAccepted()
                                    || !RecoverableImageUploads.unavailable(unavailable))
                                throw unavailable;
                            upload = uploads.renew(journal, step, shardIndex, upload, stop);
                            bind(journal, runs, run, view.capture(), upload);
                        }
                        journal.recordImage(
                                step,
                                shardIndex,
                                new RunImageSources.Upload(
                                        input.inputId(), unit, upload.source(), true));
                        uploads.discardAccepted(journal);
                        // This also observes terminal publication while QuPath was
                        // encoding/uploading.
                        delivery = runs.readSources(run);
                        restoreAccepted(journal, shardIndex, run, delivery);
                        if (!delivery.active()) return;
                    }
                }
            } catch (IOException | InferenceException | IllegalArgumentException failure) {
                RecoverableImageUploads.requireRunning(stop);
                var report = failureReport(failure);
                var retained = journal.run(step, shardIndex).imageSources().orElseThrow();
                journal.recordImages(
                        step,
                        shardIndex,
                        new RunImageSources(retained.uploads(), Optional.of(report)));
                try {
                    runs.reportSourceFailure(run, report);
                } catch (IOException | InterruptedException | InferenceException reporting) {
                    RecoverableImageUploads.requireRunning(stop);
                    if (invalidCapturedSource(failure)
                            && !(reporting instanceof InterruptedException)) {
                        failure.addSuppressed(reporting);
                        throw failure;
                    }
                    reporting.addSuppressed(failure);
                    throw reporting;
                }
                // Changed slides and damaged recovery files are not retryable upload failures,
                // even if cloud work finished. Keep their existing action-failure classification.
                RecoverableImageUploads.requireRunning(stop);
                if (invalidCapturedSource(failure)) throw failure;
                // The serialized backend transition decides COMPLETE/PARTIAL/FAILED. Awaiting that
                // outcome preserves work which finished concurrently with this image failure.
            }
        } catch (CancellationException | InterruptedException cancelled) {
            boolean interrupted = Thread.interrupted();
            try {
                runs.cancel(run);
            } catch (IOException | InterruptedException | InferenceException cancellation) {
                cancelled.addSuppressed(cancellation);
            } finally {
                if (interrupted || cancelled instanceof InterruptedException)
                    Thread.currentThread().interrupt();
            }
            throw cancelled;
        }
    }

    private static void bind(
            DurableStepJournal journal,
            DurableModelRunExecutor runs,
            ModelRunHandle run,
            SourceCaptureCodec.Capture capture,
            RunImageSources.Upload upload)
            throws IOException, InterruptedException, InferenceException {
        runs.bindSource(
                run,
                new ModelRunSourceCodec.Binding(
                        upload.inputId(),
                        upload.unitId(),
                        RecoverableImageUploads.binding(
                                journal.handles(), capture, upload.source())));
    }

    /**
     * Resolve an accepted-before-response interruption, including units with no computation yet.
     */
    private static void restoreAccepted(
            DurableStepJournal journal,
            int shardIndex,
            ModelRunHandle run,
            ModelRunSourceCodec.Delivery delivery)
            throws IOException {
        String step = run.request().producer().stepId();
        for (var binding : delivery.sourceBindings()) {
            var retained =
                    journal.run(step, shardIndex).imageSources().orElseThrow().uploads().stream()
                            .filter(
                                    value ->
                                            value.inputId() == binding.inputId()
                                                    && value.unitId() == binding.unitId())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "Accepted image has no retained upload record"));
            var input =
                    run.request().inputs().stream()
                            .filter(value -> value.inputId() == binding.inputId())
                            .findFirst()
                            .orElseThrow();
            var capture = ((SourceCaptureCodec.View) input.rasterInput().sourceView()).capture();
            if (retained.source().sealedArtifact().isEmpty()
                    || !RecoverableImageUploads.binding(
                                    journal.handles(), capture, retained.source())
                            .equals(binding.source()))
                throw new IOException("Accepted image differs from the retained upload");
            if (!retained.bindingAccepted())
                journal.recordImage(
                        step,
                        shardIndex,
                        new RunImageSources.Upload(
                                retained.inputId(), retained.unitId(), retained.source(), true));
        }
    }

    private static GatewayErrorCodec.Error failureReport(Exception failure) {
        if (failure instanceof InferenceException inference && inference.gatewayError().isPresent())
            return inference.gatewayError().orElseThrow();
        return new GatewayErrorCodec.Error(
                1,
                failure instanceof IllegalArgumentException || invalidCapturedSource(failure)
                        ? GatewayErrorCodec.Code.VALIDATION_ERROR
                        : GatewayErrorCodec.Code.SERVICE_UNAVAILABLE,
                "Could not provide an image for inference: " + failure.getMessage(),
                Optional.empty(),
                Optional.empty());
    }

    private static boolean invalidCapturedSource(Exception failure) {
        return failure instanceof SourceVersionChangedException
                || failure instanceof UnrecoverableRecoveryStateException
                || failure instanceof PreparedSourceCorruptException;
    }
}
