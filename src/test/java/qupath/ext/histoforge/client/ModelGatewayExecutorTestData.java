package qupath.ext.histoforge.client;

import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.platform.ExtensionRelease;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

final class ModelGatewayExecutorTestData {

    static final String ACTION_ID = "11111111-1111-4111-8111-111111111111";
    static final String ARTIFACT_ID = "22222222-2222-4222-8222-222222222222";
    static final String DEPLOYMENT_ID = "deployment-a";
    static final Instant ABSOLUTE_DEADLINE = Instant.parse("2026-08-16T18:00:00.000Z");
    static final Instant IDLE_DEADLINE = Instant.parse("2026-08-16T17:00:00.000Z");

    private ModelGatewayExecutorTestData() {}

    static PreparedSourceArtifact prepared(Path source, byte[] bytes, int chunkSize)
            throws IOException {
        Files.write(source, bytes);
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        "a".repeat(64),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        bytes.length,
                                        attributes.lastModifiedTime().toMillis(),
                                        Optional.ofNullable(attributes.fileKey())
                                                .map(Object::toString))),
                        7,
                        4,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1.0, 1.0));
        var start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        ACTION_ID,
                        ARTIFACT_ID,
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                        bytes.length,
                        chunkSize,
                        new SourceArtifactCodec.OriginalWsi(
                                identity,
                                0,
                                new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, 0.0, 0.0),
                                source.getFileName().toString(),
                                readerSettings(identity, 1),
                                List.of()),
                        List.of(new SourceArtifactCodec.SourceLevelRequirement(0, 0, 7, 4)));
        var seal =
                new SourceArtifactTransferCodec.SourceArtifactSealRequest(
                        CanonicalCodec.sha256(bytes));
        return new PreparedSourceArtifact(source, start, seal);
    }

    static byte[] sourceBytes(int count) {
        byte[] bytes = new byte[count];
        for (int index = 0; index < count; index++) {
            bytes[index] = (byte) (index * 17 + 3);
        }
        return bytes;
    }

    static GatewayCapabilities capabilities() throws IOException {
        return capabilities(DEPLOYMENT_ID);
    }

    static GatewayCapabilities capabilities(String deploymentId) throws IOException {
        try (var input =
                ModelGatewayExecutorTestData.class.getResourceAsStream(
                        "/contracts/gateway-capabilities-v2.json")) {
            if (input == null) {
                throw new IOException("missing gateway capabilities fixture");
            }
            var root =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var value =
                    root.getAsJsonArray("capabilityCases")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("value")
                            .deepCopy();
            value.addProperty("deployment_id", deploymentId);
            value.addProperty("auth_revision", 1);
            value.getAsJsonObject("gateway_build")
                    .addProperty("version", ExtensionRelease.current().bundledBackendVersion());
            return GatewayCapabilitiesCodec.parseGatewayCapabilities(value);
        }
    }

    static DeploymentStore storeFor(HttpServer server, Path directory) throws IOException {
        return storeFor(server, directory, DEPLOYMENT_ID);
    }

    static void serveCapabilities(HttpServer server, GatewayCapabilities capabilities) {
        serveHealth(server, capabilities.apiMajor());
        byte[] body = CanonicalCodec.canonicalBytes(GatewayCapabilitiesCodec.toJson(capabilities));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
    }

    static void serveHealth(HttpServer server, int apiMajor) {
        byte[] body =
                ("{\"api_major\":" + apiMajor + ",\"status\":\"ok\"}")
                        .getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/v1/health",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
    }

    static DeploymentStore storeFor(HttpServer server, Path directory, String deploymentId)
            throws IOException {
        Path file = directory.resolve("deployment.json");
        String url = "http://localhost:" + server.getAddress().getPort();
        Files.writeString(file, deployment(url, deploymentId).toJson().toString());
        return new DeploymentStore(file);
    }

    static Deployment deployment(String url, String deploymentId) {
        return new Deployment(
                1,
                deploymentId,
                url,
                "research-lab",
                "histoforge-gateway",
                1,
                "test-token",
                Instant.parse("2026-08-16T12:34:56.789Z"));
    }
}
