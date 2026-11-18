package qupath.ext.histoforge.client;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ModelRunLimits;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModelRunSourceCodec;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;

/** Executes one durable exact-model run lifecycle without recipe or hierarchy authority. */
public final class DurableModelRunExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DurableModelRunExecutor.class);
    private static final String RUN_STATUS_PATH = "/inference/runs/";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final GatewayTransport transport;
    private final GatewayCapabilities gatewayCapabilities;
    private final ArtifactDownloader artifacts;
    private final BackendAdmission admission;
    private final Consumer<ModelRunStatusCodec.Status> onStatus;

    /** Bind durable requests and every downloaded artifact to one capability capture. */
    public DurableModelRunExecutor(
            GatewayTransport transport, GatewayCapabilities gatewayCapabilities) {
        this(transport, gatewayCapabilities, ignored -> {});
    }

    /** Share already-fetched progress with the UI without starting a second polling loop. */
    public DurableModelRunExecutor(
            GatewayTransport transport,
            GatewayCapabilities gatewayCapabilities,
            Consumer<ModelRunStatusCodec.Status> onStatus) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.gatewayCapabilities =
                Objects.requireNonNull(gatewayCapabilities, "gatewayCapabilities");
        if (!GatewayCapabilitiesCodec.supports(gatewayCapabilities)) {
            throw new IllegalArgumentException("gateway capabilities are unsupported");
        }
        artifacts = new ArtifactDownloader(transport, gatewayCapabilities.deploymentId());
        admission = new BackendAdmission(transport, gatewayCapabilities);
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
    }

    /** Deployment captured for every request, poll, cancellation, and artifact download. */
    public String deploymentId() {
        return gatewayCapabilities.deploymentId();
    }

    /** Effective gateway-side canonical JSON ceiling used by deterministic client sharding. */
    public long maxRequestCanonicalBytes() {
        return gatewayCapabilities.limits().maxJsonRequestBytes();
    }

    /**
     * Durably submit one canonical request; exact replay is safe after an ambiguous response.
     *
     * <p>The caller must retain the same request independently before the network call. A later
     * process may call this method again with those exact bytes to recover a lost acceptance.
     */
    public ModelRunHandle submit(
            ModelRunRequestCodec.ModelRunRequest request,
            RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile,
            Collection<String> declaredClasses)
            throws IOException, InterruptedException, InferenceException {
        PreparedRequest prepared =
                prepareRequest(request, runnerCapabilities, advertisedProfile, declaredClasses);
        // Only a new POST must fit today's gateway request-body limit. Read-only adoption keeps
        // the accepted request's original shard boundaries, even after that limit changes.
        if (prepared.canonicalBytes().length > gatewayCapabilities.limits().maxJsonRequestBytes()) {
            throw new IllegalArgumentException(
                    "model run request exceeds captured gateway JSON limit");
        }
        admission.require();
        requireDeployment();
        ModelRunRequestCodec.ModelRunAccepted accepted =
                submitWithReplay(prepared.request(), prepared.canonicalBytes());
        return new ModelRunHandle(
                gatewayCapabilities.deploymentId(),
                prepared.request(),
                accepted,
                prepared.durableLimits(),
                prepared.declaredClasses());
    }

    /** Adopt one already accepted canonical request without issuing another start operation. */
    public ModelRunHandle adopt(
            ModelRunRequestCodec.ModelRunRequest request,
            RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile,
            Collection<String> declaredClasses)
            throws IOException {
        PreparedRequest prepared =
                prepareRequest(request, runnerCapabilities, advertisedProfile, declaredClasses);
        requireDeployment();
        ModelRunRequestCodec.ModelRunAccepted accepted =
                new ModelRunRequestCodec.ModelRunAccepted(
                        ModelRunRequestCodec.SCHEMA_VERSION,
                        prepared.request().actionId(),
                        prepared.request().modelRunId(),
                        ModelRunRequestCodec.requestDigest(prepared.request()));
        return new ModelRunHandle(
                gatewayCapabilities.deploymentId(),
                prepared.request(),
                accepted,
                prepared.durableLimits(),
                prepared.declaredClasses());
    }

    /** Poll once and expose only independently validated immutable completed inputs. */
    public ModelRunObservation poll(ModelRunHandle handle)
            throws IOException, InterruptedException, InferenceException {
        return poll(
                handle,
                (artifact, manifest, downloads) -> downloadCompletedInputs(handle, manifest));
    }

    /** Retain a COMPLETE result before exposing its predictions to the next recipe step. */
    public ModelRunObservation poll(ModelRunHandle handle, CompleteResultReader completeResults)
            throws IOException, InterruptedException, InferenceException {
        ModelRunHandle run = requireHandle(handle);
        Inspection inspection = inspect(run.request(), run.durableLimits().run());
        var status = inspection.status();
        try {
            onStatus.accept(status);
        } catch (RuntimeException failure) {
            logger.warn("Could not display inference progress", failure);
        }
        if (status.phase() == ModelRunStatusCodec.Phase.QUEUED
                || status.phase() == ModelRunStatusCodec.Phase.RUNNING) {
            return new ModelRunObservation.Active(status);
        }

        requirePersistedRequest(run, status);
        Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> completed = Map.of();
        if (inspection.manifest().isPresent()) {
            var manifest = inspection.manifest().orElseThrow();
            completed =
                    manifest.complete()
                            ? completeResults.read(
                                    status.terminalManifest().orElseThrow(), manifest, artifacts)
                            : downloadCompletedInputs(run, manifest);
        }
        requireDeployment();
        return new ModelRunObservation.Terminal(status, inspection.manifest(), completed);
    }

    /**
     * Inspect a saved request using its captured limits, without retrieving prediction geometry.
     * This does not require the model to remain installed or authorize resuming the analysis.
     */
    public Inspection inspect(ModelRunRequestCodec.ModelRunRequest request, ModelRunLimits limits)
            throws IOException, InterruptedException, InferenceException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limits, "limits");
        JsonElement statusValue = readStatus(request.modelRunId());
        ModelRunStatusCodec.Status unbound;
        try {
            unbound = ModelRunStatusCodec.parse(statusValue);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read durable model run", error);
        }

        Optional<ArtifactDownloader.CanonicalJson> downloadedManifest = Optional.empty();
        if (unbound.terminalManifest().isPresent()) {
            downloadedManifest =
                    Optional.of(
                            artifacts.canonicalJson(
                                    unbound.terminalManifest().orElseThrow(),
                                    GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES,
                                    "download durable model-run manifest"));
        }

        ModelRunStatusCodec.Status status;
        Optional<ModelRunPublicationCodec.Manifest> manifest = Optional.empty();
        try {
            status =
                    ModelRunStatusCodec.validateProjection(
                            statusValue,
                            request,
                            limits,
                            downloadedManifest.map(ArtifactDownloader.CanonicalJson::value));
            if (downloadedManifest.isPresent()) {
                manifest =
                        Optional.of(
                                ModelRunPublicationCodec.validateManifest(
                                        downloadedManifest.orElseThrow().value(), request, limits));
            }
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read durable model run", error);
        }

        requireDeployment();
        return new Inspection(status, manifest);
    }

    /** Validated progress and optional terminal metadata; never biological output. */
    public record Inspection(
            ModelRunStatusCodec.Status status,
            Optional<ModelRunPublicationCodec.Manifest> manifest) {}

    /** Poll one already accepted run to a terminal state without inventing replacement work. */
    public ModelRunObservation.Terminal awaitTerminal(
            ModelRunHandle handle, BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        return awaitTerminal(
                handle,
                shouldStop,
                (artifact, manifest, downloads) -> downloadCompletedInputs(handle, manifest));
    }

    public ModelRunObservation.Terminal awaitTerminal(
            ModelRunHandle handle, BooleanSupplier shouldStop, CompleteResultReader completeResults)
            throws IOException, InterruptedException, InferenceException {
        ModelRunHandle run = requireHandle(handle);
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        try {
            while (true) {
                if (stop.getAsBoolean()) {
                    cancelAndThrow(run);
                }
                ModelRunObservation observation = poll(run, completeResults);
                if (observation instanceof ModelRunObservation.Terminal terminal) {
                    return terminal;
                }
                if (stop.getAsBoolean()) {
                    cancelAndThrow(run);
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException interrupted) {
            try {
                cancel(run);
            } catch (IOException | InterruptedException | InferenceException cancellation) {
                interrupted.addSuppressed(cancellation);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    /** Request idempotent best-effort cancellation of one accepted durable run. */
    public void cancel(ModelRunHandle handle)
            throws IOException, InterruptedException, InferenceException {
        ModelRunHandle run = requireHandle(handle);
        cancel(run.request().modelRunId());
    }

    /** Cancel a recorded run even when its model or the original plugin build is unavailable. */
    public void cancel(String modelRunId)
            throws IOException, InterruptedException, InferenceException {
        String path =
                RUN_STATUS_PATH + CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token -> transport.authed(path, token).DELETE().build());
        transport.requireTargetStatus(response, 204, "cancel durable model run");
        requireDeployment();
    }

    /**
     * Read accepted image assignments before preparing more pixels or restoring unfinished work.
     */
    public ModelRunSourceCodec.Delivery readSources(ModelRunHandle handle)
            throws IOException, InterruptedException, InferenceException {
        var run = requireHandle(handle);
        String path = RUN_STATUS_PATH + run.request().modelRunId() + "/sources";
        var response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token -> transport.authed(path, token).GET().build());
        transport.requireTargetStatus(response, 200, "read model run images");
        try {
            var delivery =
                    ModelRunSourceCodec.validateDelivery(
                            canonicalControl(response.body(), "model run images"),
                            run.request(),
                            run.durableLimits().run());
            requireDeployment();
            return delivery;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read model run images", error);
        }
    }

    /** An identical retry confirms acceptance; it never replaces an earlier image assignment. */
    public void bindSource(ModelRunHandle handle, ModelRunSourceCodec.Binding binding)
            throws IOException, InterruptedException, InferenceException {
        var run = requireHandle(handle);
        ModelRunSourceCodec.validateBindings(
                List.of(binding),
                run.request(),
                ModelRunPublicationCodec.buildExpectedPlans(
                        run.request(), run.durableLimits().run()));
        byte[] body = CanonicalCodec.canonicalBytes(ModelRunSourceCodec.toJson(binding));
        String path = RUN_STATUS_PATH + run.request().modelRunId() + "/sources";
        var response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token ->
                                transport
                                        .authed(path, token)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(response, 200, "provide model run image");
        try {
            var accepted =
                    ModelRunSourceCodec.parseBinding(
                            canonicalControl(response.body(), "accepted model run image"));
            if (!accepted.equals(binding)) {
                throw new IllegalArgumentException(
                        "accepted image differs from the supplied binding");
            }
        } catch (IllegalArgumentException error) {
            throw invalidResponse("provide model run image", error);
        }
        requireDeployment();
    }

    /** Report a retained preparation/upload error without treating it as user cancellation. */
    public void reportSourceFailure(ModelRunHandle handle, GatewayErrorCodec.Error failure)
            throws IOException, InterruptedException, InferenceException {
        var run = requireHandle(handle);
        byte[] body = CanonicalCodec.canonicalBytes(GatewayErrorCodec.toJson(failure));
        if (body.length > gatewayCapabilities.limits().maxJsonRequestBytes()) {
            throw new IllegalArgumentException("source failure exceeds gateway JSON limit");
        }
        String path = RUN_STATUS_PATH + run.request().modelRunId() + "/source-failure";
        var response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token ->
                                transport
                                        .authed(path, token)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(response, 204, "report model run image failure");
        requireDeployment();
    }

    private void cancelAndThrow(ModelRunHandle handle) {
        CancellationException cancelled = new CancellationException("durable model run cancelled");
        try {
            cancel(handle);
        } catch (IOException | InterruptedException | InferenceException failure) {
            cancelled.addSuppressed(failure);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        throw cancelled;
    }

    private RunnerCapabilitiesCodec.DurableRunLimits validateRunnerRequest(
            ModelRunRequestCodec.ModelRunRequest request,
            RunnerCapabilitiesCodec.RunnerCapabilities runner,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile) {
        if (!runner.modes().contains(RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN)
                || runner.durableRun().isEmpty()) {
            throw new IllegalArgumentException(
                    "runner does not advertise durable model-run capability");
        }
        RunnerCapabilitiesCodec.DurableRunLimits durable = runner.durableRun().orElseThrow();
        RunnerAdmission.requireBackend(request.expectedBackend(), runner);
        for (var input : request.inputs()) {
            if (input.rasterInput().sourceView() instanceof RasterInputCodec.SourceView sealed) {
                RunnerAdmission.requireSourceContainer(
                        sealed.artifact().container().name(), runner);
            }
        }
        RunnerAdmission.requireResultBudgetWithin(request.resultBudget(), durable.run().result());
        ModelRunRequestCodec.validatePlanFamily(
                request, runner.finite().targetRaster(), advertisedProfile);
        ModelRunRequestCodec.validateResourceLimits(request, durable.run());
        return durable;
    }

    private PreparedRequest prepareRequest(
            ModelRunRequestCodec.ModelRunRequest request,
            RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile,
            Collection<String> declaredClasses) {
        ModelRunRequestCodec.ModelRunRequest supplied = Objects.requireNonNull(request, "request");
        byte[] canonicalRequest =
                CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(supplied));
        ModelRunRequestCodec.ModelRunRequest validated =
                ModelRunRequestCodec.validateRouteRequest(
                        CanonicalCodec.parseJson(canonicalRequest),
                        supplied.producer().moduleRef().toString(),
                        supplied.producer().stepId());
        RunnerCapabilitiesCodec.RunnerCapabilities runner =
                Objects.requireNonNull(runnerCapabilities, "runnerCapabilities");
        RunnerCapabilitiesCodec.DurableRunLimits durable =
                validateRunnerRequest(
                        validated,
                        runner,
                        Objects.requireNonNull(advertisedProfile, "advertisedProfile"));
        Set<String> classes = RunnerAdmission.normalizeDeclaredClasses(declaredClasses);
        return new PreparedRequest(validated, canonicalRequest, durable, classes);
    }

    private ModelRunRequestCodec.ModelRunAccepted submitWithReplay(
            ModelRunRequestCodec.ModelRunRequest request, byte[] canonicalRequest)
            throws IOException, InterruptedException, InferenceException {
        try {
            return submitGateway(request, canonicalRequest);
        } catch (IOException first) {
            try {
                return submitGateway(request, canonicalRequest);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private ModelRunRequestCodec.ModelRunAccepted submitGateway(
            ModelRunRequestCodec.ModelRunRequest request, byte[] canonicalRequest)
            throws IOException, InterruptedException, InferenceException {
        var module = request.producer().moduleRef();
        String path =
                "/inference/"
                        + module.owner()
                        + "/"
                        + module.name()
                        + "/"
                        + request.producer().stepId()
                        + "/runs";
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token ->
                                transport
                                        .authed(path, token)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofByteArray(
                                                        canonicalRequest))
                                        .build());
        transport.requireTargetStatus(response, 202, "submit durable model run");
        try {
            return ModelRunRequestCodec.validateAcceptance(
                    request, canonicalControl(response.body(), "durable acceptance"));
        } catch (IllegalArgumentException error) {
            throw invalidResponse("submit durable model run", error);
        }
    }

    private JsonElement readStatus(String modelRunId)
            throws IOException, InterruptedException, InferenceException {
        String path = RUN_STATUS_PATH + modelRunId;
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token -> transport.authed(path, token).GET().build());
        transport.requireTargetStatus(response, 200, "read durable model run");
        try {
            return canonicalControl(response.body(), "durable status");
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read durable model run", error);
        }
    }

    private void requirePersistedRequest(ModelRunHandle run, ModelRunStatusCodec.Status status)
            throws IOException, InterruptedException, InferenceException {
        byte[] expected = CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(run.request()));
        ArtifactDownloader.CanonicalJson downloaded =
                artifacts.canonicalJson(
                        status.requestArtifact(), expected.length, "download durable run request");
        if (!Arrays.equals(downloaded.bytes(), expected)) {
            throw invalidResponse(
                    "download durable run request",
                    new IllegalArgumentException(
                            "persisted request bytes do not match the submitted request"));
        }
    }

    private Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> downloadCompletedInputs(
            ModelRunHandle run, ModelRunPublicationCodec.Manifest manifest)
            throws IOException, InterruptedException, InferenceException {
        return ModelResultReader.read(
                manifest,
                run.request().resultBudget(),
                (input, remaining) -> {
                    var validated =
                            artifacts.featureStream(
                                    input.featureStreamArtifact(),
                                    remaining,
                                    run.declaredClasses(),
                                    input.featureCount(),
                                    input.vertexCount(),
                                    "download model input " + input.inputId());
                    var requested =
                            run.request().inputs().stream()
                                    .filter(value -> value.inputId() == input.inputId())
                                    .findFirst()
                                    .orElseThrow();
                    validated.requireInsideRaster(
                            requested.rasterInput().frame().transform().raster());
                    return validated;
                });
    }

    /** A caller-owned retention boundary, invoked only for a validated COMPLETE publication. */
    @FunctionalInterface
    public interface CompleteResultReader {
        Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> read(
                ArtifactRefCodec.ArtifactRef manifestArtifact,
                ModelRunPublicationCodec.Manifest manifest,
                ArtifactDownloader downloads)
                throws IOException, InterruptedException, InferenceException;
    }

    private ModelRunHandle requireHandle(ModelRunHandle handle) {
        ModelRunHandle run = Objects.requireNonNull(handle, "handle");
        if (!gatewayCapabilities.deploymentId().equals(run.deploymentId())) {
            throw new IllegalArgumentException(
                    "durable model-run handle belongs to a different gateway deployment");
        }
        return run;
    }

    private void requireDeployment() throws IOException {
        if (!transport.matchesCurrentDeployment(gatewayCapabilities.deploymentId())) {
            throw new GatewayTransport.DeploymentChangedException(
                    "deployment changed during durable model run; restart action");
        }
    }

    private static JsonElement canonicalControl(byte[] bytes, String path) {
        JsonElement value = CanonicalCodec.parseJson(bytes);
        if (!Arrays.equals(bytes, CanonicalCodec.canonicalBytes(value))) {
            throw new IllegalArgumentException(path + " must be canonical JSON");
        }
        return value;
    }

    private static InferenceException invalidResponse(
            String operation, IllegalArgumentException error) {
        return new InferenceException(operation + ": invalid gateway response", error);
    }

    private record PreparedRequest(
            ModelRunRequestCodec.ModelRunRequest request,
            byte[] canonicalBytes,
            RunnerCapabilitiesCodec.DurableRunLimits durableLimits,
            Set<String> declaredClasses) {
        private PreparedRequest {
            canonicalBytes = canonicalBytes.clone();
            declaredClasses = Set.copyOf(declaredClasses);
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }
}
