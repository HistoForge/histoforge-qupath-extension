package qupath.ext.histoforge.client;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;

/** Authenticated compatibility check for one exact local deployment credential. */
public final class ConnectionProbe {

    public enum Status {
        /** No complete valid local credential exists. */
        NOT_CONFIGURED,
        /** The configured gateway did not answer a valid request. */
        UNREACHABLE,
        /** The gateway answered, but rejected the local credential. */
        AUTHENTICATION_REQUIRED,
        /** Release or contract compatibility could not be established. */
        INCOMPATIBLE,
        /** The local credential and authenticated remote deployment identity disagree. */
        IDENTITY_MISMATCH,
        /** The credential now names another origin; existing consumers must be rebuilt. */
        RESTART_REQUIRED,
        /** Health, authentication, deployment identity, and every schema version agree. */
        CONNECTED
    }

    public record Result(
            Status status,
            Optional<BackendCompatibility.Decision> compatibility,
            boolean inspectionSupported,
            List<String> inferenceGpus) {
        public Result {
            inferenceGpus = List.copyOf(inferenceGpus);
        }

        /** Status-only results have no verified GPU choices. */
        public Result(
                Status status,
                Optional<BackendCompatibility.Decision> compatibility,
                boolean inspectionSupported) {
            this(status, compatibility, inspectionSupported, List.of());
        }

        private static Result status(Status status) {
            return new Result(status, Optional.empty(), false);
        }

        private static Result assessed(BackendCompatibility.Decision decision) {
            return assessed(decision, false, List.of());
        }

        private static Result assessed(
                BackendCompatibility.Decision decision,
                boolean inspectionSupported,
                List<String> inferenceGpus) {
            return new Result(
                    decision.permitsWork() ? Status.CONNECTED : Status.INCOMPATIBLE,
                    Optional.of(decision),
                    inspectionSupported,
                    inferenceGpus);
        }
    }

    private ConnectionProbe() {}

    /** Probe synchronously; call off the FX thread because this performs network requests. */
    public static Result probe(DeploymentStore store) {
        var local = store.load();
        if (local.isEmpty()) {
            return Result.status(Status.NOT_CONFIGURED);
        }
        return probe(store, new ModuleGatewayClient(store));
    }

    /** Probe against an already-shared client, refusing a credential for another origin. */
    public static Result probe(DeploymentStore store, ModuleGatewayClient client) {
        return probe(store, client, BackendCompatibility.current());
    }

    static Result probe(
            DeploymentStore store, ModuleGatewayClient client, BackendCompatibility policy) {
        var local = store.reload();
        if (local.isEmpty()) {
            return Result.status(Status.NOT_CONFIGURED);
        }
        if (!client.isBoundTo(local.orElseThrow())) {
            return Result.status(Status.RESTART_REQUIRED);
        }
        try {
            // Unsupported public API versions still need authenticated diagnosis.
            var health = client.health();
            JsonElement document = client.capabilitiesDocument();
            var current = store.reload();
            if (current.isEmpty()) return Result.status(Status.NOT_CONFIGURED);
            Deployment deployment = current.orElseThrow();
            if (!client.isBoundTo(deployment)) return Result.status(Status.RESTART_REQUIRED);
            String version = diagnosticVersion(document);
            try {
                var object = document.getAsJsonObject();
                String remoteId = string(object.get("deployment_id"));
                long revision =
                        CanonicalCodec.requireNonnegativeSafeInteger(
                                object.get("auth_revision"), "auth_revision");
                if (remoteId == null) return Result.assessed(BackendCompatibility.unknown(null));
                if (!deployment.deploymentId().equals(remoteId)
                        || deployment.authRevision() != revision) {
                    return Result.status(Status.IDENTITY_MISMATCH);
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                return Result.assessed(BackendCompatibility.unknown(null));
            }
            final GatewayCapabilities capabilities;
            try {
                capabilities = GatewayCapabilitiesCodec.parseGatewayCapabilities(document);
            } catch (IllegalArgumentException error) {
                // Display-only evidence from this same authenticated body. Never admit work
                // or guess a direction for replacing the deployed backend from partial data.
                return Result.assessed(BackendCompatibility.unknown(version));
            }
            return Result.assessed(
                    policy.evaluate(capabilities, health.apiMajor()),
                    health.apiMajor() == capabilities.apiMajor()
                            && GatewayCapabilitiesCodec.supports(capabilities),
                    capabilities.inferenceGpus());
        } catch (IOException error) {
            return afterIoFailure(store, client);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.status(Status.UNREACHABLE);
        } catch (InferenceException error) {
            if (error.statusCode() == 401) {
                return Result.status(Status.AUTHENTICATION_REQUIRED);
            }
            return error.statusCode() >= 500
                    ? Result.status(Status.UNREACHABLE)
                    : Result.assessed(BackendCompatibility.unknown(null));
        }
    }

    private static String diagnosticVersion(JsonElement document) {
        if (!document.isJsonObject()) return null;
        JsonElement build = document.getAsJsonObject().get("gateway_build");
        return build != null && build.isJsonObject()
                ? string(build.getAsJsonObject().get("version"))
                : null;
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static Result afterIoFailure(DeploymentStore store, ModuleGatewayClient client) {
        var current = store.reload();
        if (current.isEmpty()) {
            return Result.status(Status.NOT_CONFIGURED);
        }
        return Result.status(
                client.isBoundTo(current.orElseThrow())
                        ? Status.UNREACHABLE
                        : Status.RESTART_REQUIRED);
    }
}
