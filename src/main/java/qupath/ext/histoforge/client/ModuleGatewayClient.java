package qupath.ext.histoforge.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.dto.InstallRequest;
import qupath.ext.histoforge.client.dto.PublishRequest;
import qupath.ext.histoforge.contract.AdminJobCodec;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayHealth;
import qupath.ext.histoforge.contract.ModuleCatalogCodec;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.ModuleCatalogPage;
import qupath.ext.histoforge.contract.TrainingReportCodec;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;
import qupath.ext.histoforge.modules.InstalledModule;

/** Capabilities, module catalog, immutable training reports, and administrative endpoints. */
public final class ModuleGatewayClient {

    private static final Logger logger = LoggerFactory.getLogger(ModuleGatewayClient.class);
    private static final Gson GSON = new Gson();
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(15);
    private static final long MODULE_REPORT_ENVELOPE_BYTES = 1_024;

    private final GatewayTransport transport;

    /** One descriptor set captured under one exact authenticated deployment handshake. */
    public record CatalogCapture(
            GatewayCapabilities capabilities, List<InstalledModuleDescriptor> modules) {
        public CatalogCapture {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        }

        public String deploymentId() {
            return capabilities.deploymentId();
        }
    }

    public ModuleGatewayClient(DeploymentStore store) {
        this(new GatewayTransport(store));
    }

    public ModuleGatewayClient(GatewayTransport transport) {
        this.transport = transport;
    }

    /** Whether this client is bound to the persisted credential's exact remote locator. */
    public boolean isBoundTo(Deployment deployment) {
        return transport.isBoundTo(deployment);
    }

    /** Read and validate the exact unauthenticated liveness response. */
    public GatewayHealth health() throws IOException, InterruptedException, InferenceException {
        GatewayTransport.BoundedResponse response = transport.sendPublic("/health", HEALTH_TIMEOUT);
        if (response.statusCode() != 200) {
            throw new InferenceException(
                    response.statusCode(), "gateway health returned HTTP " + response.statusCode());
        }
        try {
            return GatewayCapabilitiesCodec.parseGatewayHealth(
                    CanonicalCodec.parseJson(response.body()));
        } catch (IllegalArgumentException error) {
            throw new InferenceException("gateway health returned an invalid response", error);
        }
    }

    /** Read and validate the exact authenticated deployment handshake. */
    public GatewayCapabilities capabilities()
            throws IOException, InterruptedException, InferenceException {
        try {
            return GatewayCapabilitiesCodec.parseGatewayCapabilities(capabilitiesDocument());
        } catch (IllegalArgumentException error) {
            throw new InferenceException("capabilities returned an invalid response", error);
        }
    }

    /** Fresh handshake for new work; inspection and cancellation deliberately do not use it. */
    GatewayCapabilities admissionCapabilities()
            throws IOException, InterruptedException, InferenceException {
        var health = health();
        GatewayCapabilities current = capabilities();
        if (!transport.matchesCurrent(current.deploymentId(), current.authRevision())) {
            throw new InferenceException(
                    "Connection identity changed. Reconnect before submitting work.");
        }
        var decision = BackendCompatibility.current().evaluate(current, health.apiMajor());
        if (!decision.permitsWork()) throw new InferenceException(decision.message());
        return current;
    }

    /** Bounded authenticated document for connection diagnosis; never an execution contract. */
    JsonElement capabilitiesDocument()
            throws IOException, InterruptedException, InferenceException {
        GatewayTransport.BoundedResponse response =
                transport.send(token -> transport.authed("/capabilities", token).GET().build());
        transport.requireTargetStatus(response, 200, "capabilities");
        try {
            return CanonicalCodec.parseJson(response.body());
        } catch (IllegalArgumentException error) {
            throw new InferenceException("capabilities returned an invalid response", error);
        }
    }

    /** Submit a publication job and return its gateway-assigned ID. */
    public String submitPublish(PublishRequest request)
            throws IOException, InterruptedException, InferenceException {
        return postForJobId(
                "/publish", GSON.toJson(request), "submitPublish " + request.targetRepository());
    }

    /** Submit an installation job and return its gateway-assigned ID. */
    public String submitInstall(InstallRequest request)
            throws IOException, InterruptedException, InferenceException {
        return postForJobId(
                "/install", GSON.toJson(request), "submitInstall " + request.moduleRef());
    }

    /** Read one job's current identity and phase-valid state. */
    public AdminJobStatus pollJobState(String jobId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalJobId = CanonicalCodec.requireCanonicalUuid(jobId, "job_id");
        GatewayTransport.BoundedResponse response =
                transport.send(
                        token -> transport.authed("/jobs/" + canonicalJobId, token).GET().build());
        transport.requireTargetStatus(response, 200, "pollJobState " + canonicalJobId);
        try {
            return AdminJobCodec.parseStatus(CanonicalCodec.parseJson(response.body()));
        } catch (IllegalArgumentException error) {
            throw new InferenceException("pollJobState: invalid gateway response", error);
        }
    }

    /** List the newest bounded page of retained install and publication jobs. */
    public List<AdminJobStatus> listJobs()
            throws IOException, InterruptedException, InferenceException {
        GatewayTransport.BoundedResponse response =
                transport.send(token -> transport.authed("/jobs?limit=100", token).GET().build());
        transport.requireTargetStatus(response, 200, "listJobs");
        try {
            return AdminJobCodec.parsePage(CanonicalCodec.parseJson(response.body())).jobs();
        } catch (IllegalArgumentException error) {
            throw new InferenceException("listJobs: invalid gateway response", error);
        }
    }

    /** Remove one transient job row; this does not cancel its administrative worker. */
    public void deleteJob(String jobId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalJobId = CanonicalCodec.requireCanonicalUuid(jobId, "job_id");
        GatewayTransport.BoundedResponse response =
                transport.send(
                        token ->
                                transport
                                        .authed("/jobs/" + canonicalJobId, token)
                                        .DELETE()
                                        .build());
        transport.requireTargetStatus(response, 204, "deleteJob " + canonicalJobId);
    }

    /** Capture one deployment ID and every descriptor from its exact ref-sorted catalog. */
    public CatalogCapture captureModuleCatalog()
            throws IOException, InterruptedException, InferenceException {
        GatewayCapabilities gateway = capabilities();
        if (!GatewayCapabilitiesCodec.supports(gateway)
                || !transport.matchesCurrent(gateway.deploymentId(), gateway.authRevision())) {
            throw new InferenceException(
                    "catalog requires capabilities bound to the current deployment credential");
        }
        int pageSize = gateway.limits().maxCatalogPageSize();
        long pageBytes = gateway.limits().maxCatalogEncodedBytes();
        List<InstalledModuleDescriptor> result = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        Optional<String> cursor = Optional.empty();
        String lastRef = null;

        do {
            String path = "/modules?limit=" + pageSize;
            if (cursor.isPresent()) {
                path += "&cursor=" + GatewayTransport.encodeQueryValue(cursor.orElseThrow());
            }
            String requestPath = path;
            GatewayTransport.BoundedResponse response =
                    transport.sendBounded(
                            token -> transport.authed(requestPath, token).GET().build(),
                            Math.min(
                                    pageBytes,
                                    GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES));
            transport.requireTargetStatus(response, 200, "listModuleDescriptors");

            final ModuleCatalogPage page;
            try {
                page =
                        ModuleCatalogCodec.parse(
                                CanonicalCodec.parseJson(response.body()),
                                gateway.trainingApiLimits().report().maxTextBytes());
            } catch (ModuleCatalogCodec.IncompatibleCatalogException error) {
                throw new InferenceException(error.getMessage(), error);
            } catch (IllegalArgumentException error) {
                throw new InferenceException("catalog returned an invalid response", error);
            }
            if (page.modules().size() > pageSize) {
                throw new InferenceException("catalog page exceeds negotiated entry limit");
            }
            for (InstalledModuleDescriptor descriptor : page.modules()) {
                String ref = descriptor.identity().moduleRef().toString();
                if (lastRef != null && lastRef.compareTo(ref) >= 0) {
                    throw new InferenceException("catalog pages are not globally ref-sorted");
                }
                result.add(descriptor);
                lastRef = ref;
            }
            cursor = page.nextCursor();
            if (cursor.isPresent() && !seenCursors.add(cursor.orElseThrow())) {
                throw new InferenceException("catalog repeated a continuation cursor");
            }
        } while (cursor.isPresent());

        if (!transport.matchesCurrent(gateway.deploymentId(), gateway.authRevision())) {
            throw new InferenceException("deployment credential changed during catalog capture");
        }
        return new CatalogCapture(gateway, result);
    }

    /** Fetch every current descriptor while preserving the existing current-UI projection seam. */
    public List<InstalledModuleDescriptor> listModuleDescriptors()
            throws IOException, InterruptedException, InferenceException {
        return captureModuleCatalog().modules();
    }

    /** Temporary current-UI projection; activation consumes exact descriptors directly. */
    public List<InstalledModule> listInstalled()
            throws IOException, InterruptedException, InferenceException {
        CatalogCapture capture = captureModuleCatalog();
        return capture.modules().stream()
                .map(descriptor -> new InstalledModule(capture.capabilities(), descriptor))
                .toList();
    }

    /** Delete the current installed module at one canonical ref. */
    public void deleteModule(String ref)
            throws IOException, InterruptedException, InferenceException {
        String canonicalRef =
                ModuleRef.parse(ref)
                        .orElseThrow(
                                () -> new IllegalArgumentException("invalid module ref: " + ref))
                        .toString();
        GatewayTransport.BoundedResponse response =
                transport.send(
                        token ->
                                transport
                                        .authed("/modules/" + canonicalRef, token)
                                        .DELETE()
                                        .build());
        transport.requireTargetStatus(response, 204, "deleteModule " + canonicalRef);
    }

    /** Read the immutable training report from the exact captured module content. */
    public ModuleTrainingReportResponse moduleTrainingReport(InstalledModule module)
            throws IOException, InterruptedException, InferenceException {
        Objects.requireNonNull(module, "module");
        GatewayCapabilities gateway = module.gateway();
        InstalledModuleDescriptor descriptor = module.descriptor();
        String expectedReportDigest = module.recipe().trainingReportDigest();
        if (expectedReportDigest == null) {
            throw new IllegalArgumentException("module does not declare a training report");
        }
        String ref = descriptor.identity().moduleRef().toString();
        String path =
                "/modules/"
                        + ref
                        + "/training-report?expected_content_digest="
                        + descriptor.identity().contentDigest();
        long successLimit =
                Math.addExact(
                        gateway.trainingApiLimits().report().maxReportBytes(),
                        MODULE_REPORT_ENVELOPE_BYTES);
        GatewayTransport.BoundedResponse response =
                transport.sendBoundedForDeployment(
                        gateway.deploymentId(),
                        token -> transport.authed(path, token).GET().build(),
                        status ->
                                status == 200
                                        ? successLimit
                                        : GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
        transport.requireTargetStatus(response, 200, "module training report " + ref);
        try {
            return TrainingReportCodec.parseModuleResponse(
                    CanonicalCodec.parseJson(response.body()),
                    gateway.trainingApiLimits().report(),
                    descriptor.identity(),
                    expectedReportDigest);
        } catch (IllegalArgumentException error) {
            throw new InferenceException(
                    "module training report returned an invalid response", error);
        }
    }

    private String postForJobId(String path, String json, String operation)
            throws IOException, InterruptedException, InferenceException {
        admissionCapabilities();
        GatewayTransport.BoundedResponse response = transport.sendJson(path, "POST", json);
        transport.requireTargetStatus(response, 202, operation);
        final String jobId;
        try {
            jobId = AdminJobCodec.parseAccepted(CanonicalCodec.parseJson(response.body())).jobId();
        } catch (IllegalArgumentException error) {
            throw new InferenceException(operation + ": invalid gateway response", error);
        }
        logger.debug("{} -> {}", operation, jobId);
        return jobId;
    }
}
