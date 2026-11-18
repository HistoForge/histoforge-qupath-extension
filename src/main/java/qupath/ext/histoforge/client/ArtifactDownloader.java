package qupath.ext.histoforge.client;

import com.google.gson.JsonElement;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipException;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;

/** Downloads and independently verifies immutable artifacts from one captured deployment. */
public final class ArtifactDownloader {

    private static final String JSON_MEDIA_TYPE = "application/json";

    /** Canonical JSON bytes and their parsed value after transport and identity validation. */
    record CanonicalJson(byte[] bytes, JsonElement value) {
        CanonicalJson {
            bytes = bytes.clone();
            value = Objects.requireNonNull(value, "value").deepCopy();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public JsonElement value() {
            return value.deepCopy();
        }
    }

    private final GatewayTransport transport;
    private final String deploymentId;

    ArtifactDownloader(GatewayTransport transport, String deploymentId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
    }

    /** Download one bounded canonical JSON artifact with no content coding. */
    CanonicalJson canonicalJson(
            ArtifactRefCodec.ArtifactRef reference, long maxBytes, String operation)
            throws IOException, InterruptedException, InferenceException {
        try {
            ArtifactRefCodec.ArtifactRef artifact =
                    requireShape(reference, JSON_MEDIA_TYPE, Optional.empty(), maxBytes);
            byte[] bytes = exactBytes(artifact, operation);
            JsonElement value = CanonicalCodec.parseJson(bytes);
            if (!Arrays.equals(bytes, CanonicalCodec.canonicalBytes(value))) {
                throw new IllegalArgumentException("artifact JSON is not canonical");
            }
            return new CanonicalJson(bytes, value);
        } catch (IllegalArgumentException error) {
            throw invalidArtifact(operation, error);
        }
    }

    /** Verify a completed feature artifact without retaining its polygon population. */
    FeatureStreamDecoder.ValidatedFeatureStream featureStream(
            ArtifactRefCodec.ArtifactRef reference,
            FiniteInferenceCodec.ClientResultBudget remainingBudget,
            Set<String> declaredClasses,
            long expectedFeatureCount,
            long expectedVertexCount,
            String operation)
            throws IOException, InterruptedException, InferenceException {
        try {
            ArtifactRefCodec.ArtifactRef artifact =
                    requireShape(
                            reference,
                            ModelRunPublicationCodec.FEATURE_STREAM_MEDIA_TYPE,
                            Optional.of(ModelRunPublicationCodec.FEATURE_STREAM_CONTENT_ENCODING),
                            remainingBudget.maxTransferredBytes());
            return consume(
                    artifact,
                    operation,
                    body ->
                            FeatureStreamDecoder.validate(
                                    body,
                                    remainingBudget,
                                    declaredClasses,
                                    expectedFeatureCount,
                                    expectedVertexCount));
        } catch (IllegalArgumentException | ZipException error) {
            throw invalidArtifact(operation, error);
        }
    }

    private byte[] exactBytes(ArtifactRefCodec.ArtifactRef reference, String operation)
            throws IOException, InterruptedException, InferenceException {
        return consume(
                reference,
                operation,
                body -> GatewayTransport.readBounded(body, reference.sizeBytes()));
    }

    /**
     * Read an exact artifact representation; a retaining reader must verify bytes before
     * publication.
     */
    public <T> T consume(
            ArtifactRefCodec.ArtifactRef reference, String operation, BodyReader<T> reader)
            throws IOException, InterruptedException, InferenceException {
        String path = "/artifacts/" + reference.artifactId();
        return transport.consumeForDeployment(
                deploymentId,
                token ->
                        transport
                                .authed(path, token)
                                .header("Accept-Encoding", "identity")
                                .GET()
                                .build(),
                (status, headers, body) -> {
                    if (status != 200) {
                        byte[] errorBody =
                                GatewayTransport.readBounded(
                                        body, GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
                        transport.requireTargetStatus(
                                new GatewayTransport.BoundedResponse(status, errorBody),
                                200,
                                operation);
                        throw new IllegalStateException("unreachable artifact response status");
                    }
                    requireHeaders(headers, reference, operation);
                    VerifyingInputStream verified =
                            new VerifyingInputStream(body, reference.sizeBytes());
                    T value = reader.read(new NonClosingInputStream(verified));
                    if (verified.read() != -1) {
                        throw new IOException(operation + ": decoder left artifact bytes unread");
                    }
                    verified.requireIdentity(reference, operation);
                    return value;
                });
    }

    private static ArtifactRefCodec.ArtifactRef requireShape(
            ArtifactRefCodec.ArtifactRef reference,
            String mediaType,
            Optional<String> contentEncoding,
            long maxBytes) {
        ArtifactRefCodec.ArtifactRef artifact = Objects.requireNonNull(reference, "reference");
        long limit = CanonicalCodec.requirePositiveSafeInteger(maxBytes, "max_artifact_bytes");
        if (!artifact.mediaType().equals(mediaType)
                || !artifact.contentEncoding().equals(contentEncoding)) {
            throw new IllegalArgumentException("artifact representation has the wrong media shape");
        }
        if (artifact.sizeBytes() > limit) {
            throw new IllegalArgumentException("artifact exceeds its captured download limit");
        }
        return artifact;
    }

    private static void requireHeaders(
            HttpHeaders headers, ArtifactRefCodec.ArtifactRef reference, String operation)
            throws InferenceException {
        try {
            requireSingleHeader(headers, "Content-Type", reference.mediaType());
            requireSingleHeader(headers, "Content-Length", Long.toString(reference.sizeBytes()));
            requireSingleHeader(headers, "Accept-Ranges", "bytes");
            requireSingleHeader(headers, "ETag", '"' + reference.sha256() + '"');
            if (reference.contentEncoding().isPresent()) {
                requireSingleHeader(
                        headers, "Content-Encoding", reference.contentEncoding().orElseThrow());
            } else if (!headers.allValues("Content-Encoding").isEmpty()) {
                throw new IllegalArgumentException(
                        "unexpected Content-Encoding on unencoded artifact");
            }
            if (!headers.allValues("Content-Range").isEmpty()) {
                throw new IllegalArgumentException("complete artifact response has Content-Range");
            }
        } catch (IllegalArgumentException error) {
            throw invalidArtifact(operation, error);
        }
    }

    private static void requireSingleHeader(HttpHeaders headers, String name, String expected) {
        var values = headers.allValues(name);
        if (values.size() != 1 || !values.getFirst().equals(expected)) {
            throw new IllegalArgumentException(name + " does not match the ArtifactRef");
        }
    }

    private static InferenceException invalidArtifact(String operation, Exception error) {
        return new InferenceException(operation + ": invalid immutable artifact", error);
    }

    @FunctionalInterface
    public interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    /** Counts and hashes transferred bytes while rejecting an overlong response immediately. */
    private static final class VerifyingInputStream extends FilterInputStream {

        private final MessageDigest digest;
        private final long expectedSize;
        private long count;
        private boolean verified;

        private VerifyingInputStream(InputStream input, long expectedSize) {
            super(input);
            this.expectedSize = expectedSize;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                byte[] one = {(byte) value};
                record(one, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                record(bytes, offset, read);
            }
            return read;
        }

        private void record(byte[] bytes, int offset, int length) throws IOException {
            count = Math.addExact(count, length);
            if (count > expectedSize) {
                throw new IOException("artifact body exceeds ArtifactRef.size_bytes");
            }
            digest.update(bytes, offset, length);
        }

        private void requireIdentity(ArtifactRefCodec.ArtifactRef reference, String operation)
                throws InferenceException {
            if (verified) {
                throw new IllegalStateException("artifact identity was already verified");
            }
            verified = true;
            String actual = HexFormat.of().formatHex(digest.digest());
            if (count != reference.sizeBytes() || !actual.equals(reference.sha256())) {
                throw invalidArtifact(
                        operation,
                        new IllegalArgumentException(
                                "artifact bytes do not match size_bytes and sha256"));
            }
        }
    }

    /** Prevents a nested decoder from taking ownership of the HTTP response stream. */
    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {}
    }
}
