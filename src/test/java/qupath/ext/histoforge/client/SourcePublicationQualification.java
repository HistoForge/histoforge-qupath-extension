package qupath.ext.histoforge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceArtifactSealRequest;
import qupath.ext.histoforge.platform.ClientBuildIdentityProvider;
import qupath.ext.histoforge.qualification.QualificationProbeIdentity;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

/** Opt-in Phase 7 exercise of the production Java source-publication stack. */
public final class SourcePublicationQualification {

    private SourcePublicationQualification() {}

    /** Publish, seal, verify, and release one exact original WSI through the private gateway. */
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected CREDENTIAL SOURCE START_REQUEST EXPECTED_SHA256 EVIDENCE_OUTPUT");
        }
        Path credential = requireRegularFile(args[0], "credential");
        Path source = requireRegularFile(args[1], "source");
        Path startFile = requireRegularFile(args[2], "start request");
        String expectedDigest = CanonicalCodec.requireSha256(args[3], "expected_sha256");
        Path output = evidenceTarget(args[4], credential, source, startFile);

        long totalStarted = System.nanoTime();
        SourceArtifactStartRequest start = readCanonicalStart(startFile);
        if (!(start.origin() instanceof OriginalWsi original)) {
            throw new IllegalArgumentException("qualification source must be an original WSI");
        }

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema_version", 1);
        evidence.addProperty("probe", "histoforge-java-source-publication-qualification");
        evidence.addProperty("captured_at", Instant.now().toString());
        evidence.add("source", sourceSummary(start, original));

        Throwable failure = null;
        ModelGatewayExecutor executor = null;
        boolean publicationAttempted = false;
        boolean released = false;
        try {
            evidence.add("qualification_probe", QualificationProbeIdentity.capture());
            evidence.add(
                    "client_build",
                    BuildIdentityCodec.toJson(ClientBuildIdentityProvider.capture()));
            GatewayTransport transport = new GatewayTransport(new DeploymentStore(credential));
            long capabilitiesStarted = System.nanoTime();
            GatewayCapabilities capabilities = new ModuleGatewayClient(transport).capabilities();
            evidence.addProperty("capabilities_seconds", elapsedSeconds(capabilitiesStarted));
            evidence.add("gateway", gatewaySummary(capabilities));

            long hashStarted = System.nanoTime();
            String digest = SourceArtifactLocalFiles.sha256Stable(source, start);
            evidence.addProperty("local_hash_seconds", elapsedSeconds(hashStarted));
            evidence.addProperty("source_sha256", digest);
            if (!digest.equals(expectedDigest)) {
                throw new IOException("source digest differs from the retained capture");
            }

            PreparedSourceArtifact prepared =
                    new PreparedSourceArtifact(
                            source, start, new SourceArtifactSealRequest(digest));
            executor = new ModelGatewayExecutor(transport, capabilities);
            Path retainedStart = output.resolveSibling("." + output.getFileName() + ".start.json");
            try {
                publicationAttempted = true;
                long publicationStarted = System.nanoTime();
                SourceArtifactSealed sealed = executor.publishSource(prepared, retainedStart);
                evidence.addProperty("upload_and_seal_seconds", elapsedSeconds(publicationStarted));
                requireSealedIdentity(start, digest, sealed);
                evidence.add("sealed", sealedSummary(sealed));
            } finally {
                Files.deleteIfExists(retainedStart);
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (executor != null && publicationAttempted) {
                long releaseStarted = System.nanoTime();
                try {
                    executor.releaseSource(start.artifactId());
                    released = true;
                } catch (Throwable cleanupError) {
                    if (failure == null) {
                        failure = cleanupError;
                    } else {
                        failure.addSuppressed(cleanupError);
                    }
                } finally {
                    evidence.addProperty("release_seconds", elapsedSeconds(releaseStarted));
                }
            }
            if (executor != null) {
                executor.close();
            }
        }

        evidence.addProperty("source_released", released);
        evidence.addProperty("success", failure == null && released);
        evidence.addProperty("total_seconds", elapsedSeconds(totalStarted));
        if (failure != null) {
            evidence.addProperty("failure_type", failure.getClass().getName());
            evidence.addProperty(
                    "failure_message",
                    sanitizedMessage(failure, credential, source, startFile, output));
        }
        evidence.addProperty("evidence_digest", CanonicalCodec.canonicalDigest(evidence));
        publishEvidence(output, evidence);
        System.out.println("PHASE7_JAVA_SOURCE_PUBLICATION_EVIDENCE=" + output);

        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static SourceArtifactStartRequest readCanonicalStart(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        final SourceArtifactStartRequest start;
        try {
            start = SourceArtifactCodec.parseStartRequest(CanonicalCodec.parseJson(bytes));
        } catch (IllegalArgumentException error) {
            throw new IOException("source start request is invalid", error);
        }
        byte[] canonical = CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start));
        if (!Arrays.equals(bytes, canonical)) {
            throw new IOException("source start request is not canonical JSON");
        }
        return start;
    }

    private static JsonObject sourceSummary(
            SourceArtifactStartRequest start, OriginalWsi original) {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", start.artifactId());
        value.addProperty("creation_action_id", start.creationActionId());
        value.addProperty("start_request_digest", SourceArtifactCodec.startRequestDigest(start));
        value.addProperty("container", start.container().name());
        value.addProperty("total_bytes", start.totalBytes());
        value.addProperty("chunk_size_bytes", start.chunkSizeBytes());
        value.addProperty(
                "chunk_count",
                SourceArtifactCodec.chunkCount(start.totalBytes(), start.chunkSizeBytes()));
        value.addProperty("upload_concurrency", ModelGatewayExecutor.MAX_CONCURRENT_SOURCE_CHUNKS);
        value.addProperty("source_adapter_id", original.source().sourceAdapterId());
        value.addProperty("width", original.source().width());
        value.addProperty("height", original.source().height());
        value.addProperty("pyramid_series_index", original.pyramidSeriesIndex());
        JsonArray levels = new JsonArray();
        start.requiredLevels().forEach(level -> levels.add(SourceArtifactCodec.toJson(level)));
        value.add("required_levels", levels);
        return value;
    }

    private static JsonObject gatewaySummary(GatewayCapabilities capabilities) {
        JsonObject value = new JsonObject();
        value.addProperty("deployment_id", capabilities.deploymentId());
        value.addProperty("auth_revision", capabilities.authRevision());
        value.add("gateway_build", BuildIdentityCodec.toJson(capabilities.gatewayBuild()));
        value.addProperty("runner_registry_digest", capabilities.runnerRegistryDigest());
        value.addProperty(
                "max_artifact_bytes", capabilities.sourceArtifactLimits().maxArtifactBytes());
        value.addProperty("max_chunk_bytes", capabilities.sourceArtifactLimits().maxChunkBytes());
        return value;
    }

    private static JsonObject sealedSummary(SourceArtifactSealed sealed) {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", sealed.artifact().artifactId());
        value.addProperty("creation_action_id", sealed.artifact().creationActionId());
        value.addProperty("start_request_digest", sealed.artifact().startRequestDigest());
        value.addProperty("bytes_digest", sealed.artifact().bytesDigest());
        value.addProperty("manifest_digest", sealed.artifact().manifestDigest());
        value.addProperty("container", sealed.artifact().container().name());
        value.addProperty("absolute_expires_at", sealed.absoluteExpiresAt().toString());
        return value;
    }

    private static void requireSealedIdentity(
            SourceArtifactStartRequest start, String digest, SourceArtifactSealed sealed)
            throws IOException {
        if (!sealed.artifact().artifactId().equals(start.artifactId())
                || !sealed.artifact().creationActionId().equals(start.creationActionId())
                || !sealed.artifact()
                        .startRequestDigest()
                        .equals(SourceArtifactCodec.startRequestDigest(start))
                || !sealed.artifact().bytesDigest().equals(digest)
                || sealed.artifact().container() != start.container()) {
            throw new IOException("sealed source identity differs from the submitted source");
        }
    }

    private static Path requireRegularFile(String value, String field) throws IOException {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(field + " must be a regular file");
        }
        return path;
    }

    private static Path evidenceTarget(String value, Path... inputs) throws IOException {
        Path target = Path.of(value).toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("evidence output parent must already exist");
        }
        for (Path input : inputs) {
            if (target.equals(input)) {
                throw new IOException("evidence output must differ from every input");
            }
        }
        return target;
    }

    private static void publishEvidence(Path target, JsonObject evidence) throws IOException {
        byte[] canonical = CanonicalCodec.canonicalBytes(evidence);
        byte[] content = Arrays.copyOf(canonical, canonical.length + 1);
        content[content.length - 1] = '\n';
        Path temporary =
                Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sanitizedMessage(Throwable error, Path... privatePaths) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String result = message;
        for (Path path : privatePaths) {
            result = result.replace(path.toString(), "<private-path>");
        }
        return new String(result.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static double elapsedSeconds(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }
}
