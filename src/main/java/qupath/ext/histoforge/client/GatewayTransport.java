package qupath.ext.histoforge.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntToLongFunction;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;

/** Shared authenticated HTTP transport for every gateway domain client. */
public final class GatewayTransport {

    /** A captured action can no longer address its exact gateway deployment. */
    public static final class DeploymentChangedException extends IOException {

        public DeploymentChangedException(String message) {
            super(message);
        }
    }

    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    static final HttpClient.Version HTTP_VERSION = HttpClient.Version.HTTP_1_1;
    public static final long MAX_BUFFERED_CONTROL_RESPONSE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_MODAL_RESULT_HANDOFFS = 20;
    private static final String MODAL_RESULT_QUERY_PREFIX = "__modal_attempt_token=";

    /** Status plus a response body whose byte ceiling was enforced while streaming. */
    public record BoundedResponse(int statusCode, byte[] body) {
        public BoundedResponse {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String utf8Body() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Reads one authenticated response body while the transport retains close ownership. */
    @FunctionalInterface
    interface StreamingBodyReader<T> {
        T read(int statusCode, HttpHeaders headers, InputStream body)
                throws IOException, InferenceException;
    }

    private final DeploymentStore store;
    private final String apiBase;
    private final HttpClient http;
    private volatile Deployment credential;

    /** Build a transport bound to the deployment location in the local credential store. */
    public GatewayTransport(DeploymentStore store) {
        Deployment deployment =
                store.load()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "not connected: ~/.histoforge/deployment.json"
                                                        + " missing — run `histoforge deploy`"));
        this.store = store;
        this.credential = deployment;
        this.apiBase = deployment.gatewayUrl() + "/v1";
        this.http =
                HttpClient.newBuilder()
                        .version(HTTP_VERSION)
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /** Whether this transport is bound to the credential's exact remote locator. */
    public boolean isBoundTo(Deployment deployment) {
        return apiBase.equals(deployment.gatewayUrl() + "/v1")
                && credential.modalAppName().equals(deployment.modalAppName());
    }

    /** Whether the latest local credential matches one authenticated handshake exactly. */
    boolean matchesCurrent(String deploymentId, long authRevision) throws IOException {
        Deployment current = currentCredential();
        return current.deploymentId().equals(deploymentId)
                && current.authRevision() == authRevision;
    }

    /**
     * Whether the latest credential still addresses the captured deployment across token rotation.
     */
    public boolean matchesCurrentDeployment(String deploymentId) throws IOException {
        return currentCredential().deploymentId().equals(deploymentId);
    }

    String apiBase() {
        return apiBase;
    }

    HttpRequest.Builder authed(String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token);
    }

    /** Send one unauthenticated bounded control request. */
    BoundedResponse sendPublic(String path, Duration timeout)
            throws IOException, InterruptedException {
        currentCredential();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + path))
                        .timeout(timeout)
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return new BoundedResponse(
                    response.statusCode(), readBounded(body, MAX_BUFFERED_CONTROL_RESPONSE_BYTES));
        }
    }

    /**
     * Send from the current credential. A 401 reloads the credential once and retries at the same
     * fixed origin; endpoint changes require rebuilding the transport.
     */
    BoundedResponse send(Function<String, HttpRequest> build)
            throws IOException, InterruptedException {
        return sendBounded(build, MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
    }

    /** Send only while the current credential still names the captured deployment. */
    BoundedResponse sendForDeployment(String deploymentId, Function<String, HttpRequest> build)
            throws IOException, InterruptedException {
        return sendBoundedForDeployment(deploymentId, build, MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
    }

    /** Send a target JSON request through the shared authentication path. */
    BoundedResponse sendJson(String path, String method, String json)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body =
                HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        return send(
                token ->
                        authed(path, token)
                                .header("Content-Type", "application/json")
                                .method(method, body)
                                .build());
    }

    /** Stream one authenticated response into memory only up to the caller's negotiated limit. */
    BoundedResponse sendBounded(Function<String, HttpRequest> build, long maxBytes)
            throws IOException, InterruptedException {
        return sendBoundedForDeployment(null, build, maxBytes);
    }

    BoundedResponse sendBoundedForDeployment(
            String deploymentId, Function<String, HttpRequest> build, long maxBytes)
            throws IOException, InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("response byte limit must be positive");
        }
        return sendBoundedForDeployment(deploymentId, build, ignored -> maxBytes);
    }

    /** Select the streaming byte ceiling after receiving status but before consuming the body. */
    BoundedResponse sendBoundedForDeployment(
            String deploymentId,
            Function<String, HttpRequest> build,
            IntToLongFunction maxBytesForStatus)
            throws IOException, InterruptedException {
        Objects.requireNonNull(maxBytesForStatus, "maxBytesForStatus");
        HttpResponse<InputStream> response = sendStream(deploymentId, build);
        final long maxBytes;
        try {
            maxBytes = maxBytesForStatus.applyAsLong(response.statusCode());
        } catch (RuntimeException error) {
            response.body().close();
            throw error;
        }
        if (maxBytes <= 0) {
            response.body().close();
            throw new IllegalArgumentException("response byte limit must be positive");
        }
        try (InputStream body = response.body()) {
            return new BoundedResponse(response.statusCode(), readBounded(body, maxBytes));
        }
    }

    /**
     * Submit one long-running JSON POST and follow only Modal's same-request result URL.
     *
     * <p>Modal converts a web request that runs beyond its HTTP wait window into a 303. The
     * continuation is a GET to the original origin and path with one opaque Modal attempt token.
     * This method accepts only that exact handoff; the shared client continues to reject every
     * other redirect.
     */
    BoundedResponse sendModalResultPostForDeployment(
            String deploymentId, String path, byte[] body, Duration totalTimeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("total timeout must be positive");
        }

        URI original = URI.create(apiBase + path);
        URI continuation = null;
        long deadline = Math.addExact(System.nanoTime(), totalTimeout.toNanos());
        int handoffs = 0;
        while (true) {
            URI requestUri = continuation == null ? original : continuation;
            boolean initial = continuation == null;
            HttpResponse<InputStream> response =
                    sendStreamUntil(
                            deploymentId,
                            deadline,
                            (token, requestTimeout) -> {
                                HttpRequest.Builder request =
                                        HttpRequest.newBuilder(requestUri)
                                                .timeout(requestTimeout)
                                                .header("Authorization", "Bearer " + token);
                                if (initial) {
                                    return request.header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                            .build();
                                }
                                return request.GET().build();
                            });
            if (response.statusCode() != 303) {
                try (InputStream responseBody = response.body()) {
                    return new BoundedResponse(
                            response.statusCode(),
                            readBoundedUntil(
                                    responseBody, MAX_BUFFERED_CONTROL_RESPONSE_BYTES, deadline));
                }
            }

            try (InputStream ignored = response.body()) {
                if (++handoffs > MAX_MODAL_RESULT_HANDOFFS) {
                    throw new IOException("Modal result continuation limit exceeded");
                }
                URI next = requireModalResultLocation(response.headers(), original);
                if (continuation != null && !continuation.equals(next)) {
                    throw new IOException("Modal result continuation changed");
                }
                continuation = next;
            }
        }
    }

    /**
     * Consume one deployment-bound response without buffering it in the transport.
     *
     * <p>The callback owns domain-specific header and byte validation. This method owns
     * authentication, token refresh, redirect policy, and closing the response body on every path.
     */
    <T> T consumeForDeployment(
            String deploymentId, Function<String, HttpRequest> build, StreamingBodyReader<T> reader)
            throws IOException, InterruptedException, InferenceException {
        Objects.requireNonNull(reader, "reader");
        HttpResponse<InputStream> response = sendStream(deploymentId, build);
        try (InputStream body = response.body()) {
            return reader.read(response.statusCode(), response.headers(), body);
        }
    }

    /** Require one exact target-route status and decode every non-success as a closed error. */
    void requireTargetStatus(BoundedResponse response, int expected, String operation)
            throws InferenceException {
        if (response.statusCode() == expected) {
            return;
        }
        if (response.statusCode() / 100 == 2) {
            throw new InferenceException(
                    response.statusCode(),
                    operation
                            + " returned HTTP "
                            + response.statusCode()
                            + "; expected "
                            + expected);
        }
        try {
            GatewayErrorCodec.Error error =
                    GatewayErrorCodec.parse(CanonicalCodec.parseJson(response.body()));
            if (GatewayErrorCodec.httpStatus(error.code()) != response.statusCode()) {
                throw new IllegalArgumentException("HTTP status does not match GatewayError code");
            }
            throw new InferenceException(response.statusCode(), error);
        } catch (InferenceException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new InferenceException(
                    response.statusCode(), operation + ": invalid typed gateway error response");
        }
    }

    private HttpResponse<InputStream> sendStream(
            String deploymentId, Function<String, HttpRequest> build)
            throws IOException, InterruptedException {
        Deployment current = currentCredential();
        requireDeployment(current, deploymentId);
        HttpResponse<InputStream> response =
                http.send(build.apply(current.token()), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 401) {
            return response;
        }
        response.body().close();
        Deployment refreshed = currentCredential();
        requireDeployment(refreshed, deploymentId);
        return http.send(build.apply(refreshed.token()), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<InputStream> sendStreamUntil(
            String deploymentId, long deadline, BiFunction<String, Duration, HttpRequest> build)
            throws IOException, InterruptedException {
        Deployment current = currentCredential();
        requireDeployment(current, deploymentId);
        HttpResponse<InputStream> response =
                http.send(
                        build.apply(current.token(), remaining(deadline)),
                        HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 401) {
            return response;
        }
        response.body().close();
        Deployment refreshed = currentCredential();
        requireDeployment(refreshed, deploymentId);
        return http.send(
                build.apply(refreshed.token(), remaining(deadline)),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void requireDeployment(Deployment current, String expected) throws IOException {
        if (expected != null && !current.deploymentId().equals(expected)) {
            throw new DeploymentChangedException("deployment changed; restart action");
        }
    }

    private static Duration remaining(long deadline) throws HttpTimeoutException {
        long nanoseconds = deadline - System.nanoTime();
        if (nanoseconds <= 0) {
            throw new HttpTimeoutException("Modal result continuation timed out");
        }
        return Duration.ofNanos(nanoseconds);
    }

    private static URI requireModalResultLocation(HttpHeaders headers, URI original)
            throws IOException {
        var locations = headers.allValues("Location");
        if (locations.size() != 1) {
            throw new IOException("Modal result continuation requires one Location header");
        }
        final URI target;
        try {
            target = original.resolve(URI.create(locations.get(0)));
        } catch (IllegalArgumentException error) {
            throw new IOException("Modal result continuation has an invalid Location", error);
        }
        if (target.isOpaque()
                || target.getRawUserInfo() != null
                || target.getRawFragment() != null
                || !Objects.equals(original.getScheme(), target.getScheme())
                || !Objects.equals(original.getRawAuthority(), target.getRawAuthority())
                || !Objects.equals(original.getRawPath(), target.getRawPath())) {
            throw new IOException("Modal result continuation changed the request target");
        }
        String query = target.getRawQuery();
        if (query == null || !query.startsWith(MODAL_RESULT_QUERY_PREFIX)) {
            throw new IOException("Modal result continuation has an unexpected query");
        }
        String token = query.substring(MODAL_RESULT_QUERY_PREFIX.length());
        if (token.isEmpty() || token.indexOf('&') >= 0 || token.indexOf(';') >= 0) {
            throw new IOException("Modal result continuation has an invalid attempt token");
        }
        return target;
    }

    static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > maxBytes) {
                throw new IOException("gateway response exceeds negotiated byte limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] readBoundedUntil(InputStream input, long maxBytes, long deadline)
            throws IOException, InterruptedException {
        long waitNanoseconds = remaining(deadline).toNanos();
        var read = new FutureTask<byte[]>(() -> readBounded(input, maxBytes));
        Thread.ofVirtual().name("histoforge-gateway-body").start(read);
        try {
            return read.get(waitNanoseconds, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            read.cancel(true);
            HttpTimeoutException timeout =
                    new HttpTimeoutException("Modal result continuation timed out");
            timeout.initCause(error);
            closeAfterFailure(input, timeout);
            throw timeout;
        } catch (InterruptedException error) {
            read.cancel(true);
            closeAfterFailure(input, error);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException ioError) {
                throw ioError;
            }
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IOException("gateway response body read failed", cause);
        }
    }

    private static void closeAfterFailure(InputStream input, Throwable failure) {
        try {
            input.close();
        } catch (IOException closeError) {
            failure.addSuppressed(closeError);
        }
    }

    /** Percent-encode one opaque UTF-8 query value without interpreting its contents. */
    static String encodeQueryValue(String value) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : bytes) {
            int octet = Byte.toUnsignedInt(item);
            if ((octet >= 'A' && octet <= 'Z')
                    || (octet >= 'a' && octet <= 'z')
                    || (octet >= '0' && octet <= '9')
                    || octet == '-'
                    || octet == '.'
                    || octet == '_'
                    || octet == '~') {
                encoded.append((char) octet);
            } else {
                encoded.append('%');
                encoded.append(hex[octet >>> 4]);
                encoded.append(hex[octet & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private Deployment currentCredential() throws IOException {
        Deployment current = credential;
        Optional<Deployment> loaded = store.reload();
        if (loaded.isEmpty()) {
            throw new IOException("deployment credential is missing or invalid");
        }
        Deployment fresh = loaded.orElseThrow();
        if (!isBoundTo(fresh)) {
            throw new DeploymentChangedException("deployment location changed; restart QuPath");
        }
        boolean replacementDeployment = !fresh.deploymentId().equals(current.deploymentId());
        boolean validSuccessor =
                replacementDeployment
                        || fresh.authRevision() > current.authRevision()
                        || (fresh.authRevision() == current.authRevision()
                                && fresh.token().equals(current.token()));
        if (!validSuccessor) {
            throw new IOException("deployment credential revision moved backward");
        }
        credential = fresh;
        return fresh;
    }
}
