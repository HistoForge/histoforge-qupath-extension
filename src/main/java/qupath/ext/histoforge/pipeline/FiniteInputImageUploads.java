package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.client.SourceTransferProgress;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.IntRect;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.SourceArtifactPlanning;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.servers.ImageServer;

/** Uploads each finite input on demand while the caller holds its existing image lease. */
final class FiniteInputImageUploads implements RecipeExecutor.InputImageUpload, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(FiniteInputImageUploads.class);

    private final ModelGatewayExecutor gateway;
    private final SourceArtifactWriter writer;
    private final ImageServer<BufferedImage> server;
    private final SourceArtifactWriter.SourceRevisionVerifier verifier;
    private final SourcePlanningLimits limits;
    private final String actionId;
    private final Path workspace;
    private final Supplier<String> artifactIds;
    private final Consumer<SourceTransferProgress> progress;
    private final Set<SourceCaptureCodec.Capture> captures;
    private final PreparedImageBudget preparedImages;
    private final Set<String> issuedIds = new LinkedHashSet<>();
    private final Set<String> uploads = new LinkedHashSet<>();
    private SourceCaptureCodec.Binding reusable;

    FiniteInputImageUploads(
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            ImageServer<BufferedImage> server,
            SourceArtifactWriter.SourceRevisionVerifier verifier,
            SourcePlanningLimits limits,
            String actionId,
            Path workspace,
            Supplier<String> artifactIds,
            Consumer<SourceTransferProgress> progress,
            Set<SourceCaptureCodec.Capture> captures,
            PreparedImageBudget preparedImages) {
        this.gateway = Objects.requireNonNull(gateway);
        this.writer = Objects.requireNonNull(writer);
        this.server = Objects.requireNonNull(server);
        this.verifier = Objects.requireNonNull(verifier);
        this.limits = Objects.requireNonNull(limits);
        this.actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
        this.workspace = Objects.requireNonNull(workspace);
        this.artifactIds = Objects.requireNonNull(artifactIds);
        this.progress = Objects.requireNonNull(progress);
        this.captures = Set.copyOf(captures);
        this.preparedImages = Objects.requireNonNull(preparedImages);
    }

    @Override
    public SourceCaptureCodec.Binding publish(
            RasterInputCodec.ModelRasterInput input,
            ModuleSnapshot.ModelSnapshot model,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        requireRunning(shouldStop);
        if (!(input.sourceView() instanceof SourceCaptureCodec.View view)
                || !captures.contains(view.capture())) {
            throw new IllegalArgumentException("input does not belong to this source preparation");
        }
        var transform = input.frame().transform();
        var raster = transform.raster();
        IntRect required =
                SourceViewGeometry.requiredCapturedSourcePixelRect(
                                view, transform, new IntRect(0, 0, raster.width(), raster.height()))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "finite input has no real source pixels"));
        if (reusable != null
                && reusable.captureDigest().equals(view.capture().captureDigest())
                && contains(reusable.sourceRectAtLevel(), required)
                && model.capabilities()
                        .sourceContainers()
                        .contains(reusable.artifact().container())) {
            return reusable;
        }

        // The previous finite call has ended. Its historical source identity stays in the result,
        // but its cloud image need not occupy an upload slot throughout the rest of the recipe.
        if (reusable != null) {
            gateway.releaseSource(reusable.artifact().artifactId());
            uploads.remove(reusable.artifact().artifactId());
            reusable = null;
        }
        var imagePlan =
                SourceArtifactPlanning.capturedImage(
                        view.capture(), required, model.capabilities().sourceContainers(), limits);
        String id = CanonicalCodec.requireCanonicalUuid(artifactIds.get(), "source_artifact_id");
        if (!issuedIds.add(id))
            throw new IllegalStateException("source artifact IDs must be unique");
        String suffix =
                imagePlan.container() == SourceArtifactCodec.SourceContainer.PNG
                        ? ".png"
                        : ".ome.tif";
        Path imageFile = workspace.resolve(id + suffix);
        Path startFile = workspace.resolve(id + ".start.json");
        var preparation = preparedImages.acquire(shouldStop);
        preparation.bind(imageFile);
        Throwable failure = null;
        try {
            progress.accept(SourceTransferProgress.preparing());
            var prepared =
                    writer.materializeCapturedImage(
                            view.capture(), imagePlan, server, imageFile, actionId, id, verifier);
            requireRunning(shouldStop);
            uploads.add(id);
            var sealed = gateway.publishSource(prepared, startFile, progress);
            reusable =
                    new SourceCaptureCodec.Binding(
                            view.capture().captureDigest(), sealed.artifact(), required);
            return reusable;
        } catch (IOException
                | InterruptedException
                | InferenceException
                | RuntimeException
                | Error stopped) {
            failure = stopped;
            throw stopped;
        } finally {
            // Finite calls have no file-based resume path. Sealed bytes are already in the source
            // store; failed preparations are retryable by a new action, not by retaining this file.
            try {
                Files.deleteIfExists(imageFile);
                Files.deleteIfExists(startFile);
            } catch (IOException cleanupFailure) {
                if (failure == null) throw cleanupFailure;
                failure.addSuppressed(cleanupFailure);
            } finally {
                preparation.close();
            }
        }
    }

    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        for (String id : uploads) {
            try {
                gateway.releaseSource(id);
            } catch (InterruptedException failure) {
                interrupted = true;
            } catch (IOException | InferenceException | RuntimeException failure) {
                logger.warn("Failed to release finite input image {}", id, failure);
            }
        }
        uploads.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean contains(IntRect available, IntRect required) {
        return required.x() >= available.x()
                && required.y() >= available.y()
                && (long) required.x() + required.width()
                        <= (long) available.x() + available.width()
                && (long) required.y() + required.height()
                        <= (long) available.y() + available.height();
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean())
            throw new CancellationException("image preparation cancelled");
    }
}
