package qupath.ext.histoforge.cloud;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Owns the active AWS login and its S3 client. A newer login, restore or logout supersedes older
 * authentication work. QuPath's service-loaded image builder obtains this instance after extension
 * installation; callers obtain a client here so expiring role credentials can be refreshed.
 */
public class AwsCredentialManager {

    private static final Logger logger = LoggerFactory.getLogger(AwsCredentialManager.class);

    /** Leave five minutes for a request to finish before role credentials expire. */
    private static final long REFRESH_BUFFER_SECONDS = 300;

    private static final Executor RESTORE_EXECUTOR =
            command -> Thread.ofVirtual().name("histoforge-aws-restore").start(command);

    private static volatile AwsCredentialManager instance;

    private final S3CredentialStore store;
    private final SsoAuthFlow ssoFlow;
    private final Object refreshLock = new Object();
    private long authenticationGeneration;
    private LoginOperation activeLogin;

    private record LoginOperation(
            long generation,
            S3ConnectionConfig config,
            SsoAuthFlow.LoginAttempt flowAttempt,
            CompletableFuture<Void> completion) {}

    /**
     * Publishes client, credentials and connection together. Replacement happens under refreshLock;
     * a reader takes one snapshot so it cannot mix fields from different logins.
     */
    private record State(
            S3Client s3Client,
            AwsCredentials credentials,
            long credentialsExpiresAt,
            S3ConnectionConfig currentConfig) {

        static final State EMPTY = new State(null, null, 0L, null);
    }

    private volatile State state = State.EMPTY;

    public AwsCredentialManager(S3CredentialStore store) {
        this(store, new SsoAuthFlow(store));
    }

    AwsCredentialManager(S3CredentialStore store, SsoAuthFlow ssoFlow) {
        this.store = store;
        this.ssoFlow = ssoFlow;
    }

    public static AwsCredentialManager getInstance() {
        return instance;
    }

    public static void setInstance(AwsCredentialManager manager) {
        instance = manager;
    }

    /**
     * Returns the current client, refreshing an expiring session first. A missing or unsuccessful
     * session yields null; the caller must request sign-in rather than use an anonymous client.
     */
    public S3Client getS3Client() {
        State observed = state;
        if (!needsRefresh(observed)) return observed.s3Client();
        synchronized (refreshLock) {
            if (needsRefresh(state)) refreshCredentials();
            return state.s3Client();
        }
    }

    /** Starts browser approval and returns completion for this specific login attempt. */
    public CompletableFuture<Void> login(S3ConnectionConfig config) {
        LoginOperation previous;
        LoginOperation operation;
        synchronized (refreshLock) {
            previous = activeLogin;
            long generation = ++authenticationGeneration;
            operation =
                    new LoginOperation(
                            generation,
                            config,
                            ssoFlow.login(config.ssoStartUrl(), config.ssoRegion()),
                            new CompletableFuture<>());
            activeLogin = operation;
        }
        cancelOperation(previous);

        operation
                .flowAttempt()
                .completion()
                .whenComplete((result, failure) -> finishLogin(operation, result, failure));
        return operation.completion();
    }

    /** Restore saved credentials without blocking QuPath startup. */
    public CompletableFuture<Boolean> restoreSavedSession() {
        LoginOperation previous;
        long operation;
        synchronized (refreshLock) {
            previous = activeLogin;
            activeLogin = null;
            operation = ++authenticationGeneration;
        }
        cancelOperation(previous);
        return CompletableFuture.supplyAsync(() -> tryRestore(operation), RESTORE_EXECUTOR);
    }

    private boolean tryRestore(long operation) {
        Optional<S3ConnectionConfig> savedConfig = store.loadConfig();
        if (savedConfig.isEmpty()) return false;

        S3ConnectionConfig config = savedConfig.get();
        synchronized (refreshLock) {
            if (operation != authenticationGeneration) {
                return false;
            }
            State old = state;
            if (old.credentials() == null) {
                state = new State(null, null, 0L, config);
            }
        }
        Optional<SsoAuthFlow.AuthenticationResult> result = ssoFlow.tryRestore(config.ssoRegion());
        if (result.isPresent() && applyAuthentication(result.get(), config, operation, null)) {
            logger.info("Restored AWS session from saved tokens");
            return true;
        }
        return false;
    }

    public boolean isAuthenticated() {
        State snapshot = state;
        return snapshot.credentials() != null && snapshot.s3Client() != null;
    }

    public S3ConnectionConfig getCurrentConfig() {
        return state.currentConfig();
    }

    public S3CredentialStore getStore() {
        return store;
    }

    /**
     * Sign out: clear in-memory credentials, close the S3 client, and delete the saved SSO token.
     * Keep the connection URL, region, bucket and prefix so Edit Connection can prefill them. After
     * this call any subsequent {@link #restoreSavedSession()} returns false until the user
     * completes a fresh {@link #login(S3ConnectionConfig)}.
     *
     * <p>Used by Edit Connection. Contrast with {@link #invalidate()}, which only drops in-memory
     * credentials and leaves on-disk tokens intact so the credential-refresh fallback path can
     * recover silently on session expiry.
     */
    public void logout() throws IOException {
        LoginOperation login;
        IOException clearFailure = null;
        synchronized (refreshLock) {
            authenticationGeneration++;
            login = activeLogin;
            activeLogin = null;
            State old = state;
            closeClient(old.s3Client(), "logout");
            state = new State(null, null, 0L, old.currentConfig());
            try {
                store.clearSsoToken();
            } catch (IOException failure) {
                clearFailure = failure;
            }
        }
        cancelOperation(login);
        if (clearFailure != null) {
            throw clearFailure;
        }
    }

    /**
     * Cancel the specified SSO login if it is still the active attempt. A stale dialog cannot
     * cancel a newer dialog's login.
     */
    public void cancelLogin(CompletableFuture<Void> expectedLogin) {
        LoginOperation login;
        synchronized (refreshLock) {
            if (activeLogin == null || activeLogin.completion() != expectedLogin) {
                return;
            }
            authenticationGeneration++;
            login = activeLogin;
            activeLogin = null;
        }
        cancelOperation(login);
    }

    /**
     * Drops the live session while keeping saved tokens and connection fields available for a
     * subsequent login or restore. It does not delete credentials from disk.
     */
    public void invalidate() {
        LoginOperation login;
        synchronized (refreshLock) {
            authenticationGeneration++;
            login = activeLogin;
            activeLogin = null;
            State old = state;
            closeClient(old.s3Client(), "invalidate");
            state = new State(null, null, 0L, old.currentConfig());
        }
        cancelOperation(login);
    }

    private static boolean needsRefresh(State snapshot) {
        if (snapshot.credentials() == null || snapshot.credentialsExpiresAt() <= 0) return false;
        long refreshAt = snapshot.credentialsExpiresAt() - REFRESH_BUFFER_SECONDS;
        return refreshAt <= Instant.now().getEpochSecond();
    }

    private void refreshCredentials() {
        S3ConnectionConfig config = state.currentConfig();
        long operation = authenticationGeneration;
        if (config == null) {
            logger.warn("Cannot refresh credentials — no region available");
            invalidate();
            return;
        }

        logger.info("Refreshing expired AWS role credentials...");
        Optional<SsoAuthFlow.AuthenticationResult> result = ssoFlow.tryRestore(config.ssoRegion());
        if (result.isPresent()) {
            if (applyAuthentication(result.get(), config, operation, null)) {
                logger.info("AWS role credentials refreshed successfully");
            }
        } else {
            logger.warn("Failed to refresh AWS credentials — SSO session may have expired");
            invalidate();
        }
    }

    private void finishLogin(
            LoginOperation operation, SsoAuthFlow.AuthenticationResult result, Throwable failure) {
        if (failure != null) {
            synchronized (refreshLock) {
                if (activeLogin == operation) {
                    activeLogin = null;
                }
            }
            if (failure instanceof CancellationException) {
                operation.completion().cancel(false);
            } else {
                operation.completion().completeExceptionally(failure);
            }
            return;
        }

        try {
            if (!applyAuthentication(
                    result, operation.config(), operation.generation(), operation)) {
                operation
                        .completion()
                        .completeExceptionally(
                                new CancellationException(
                                        "AWS login was superseded by a newer authentication action"));
                return;
            }
            logger.info("AWS credentials obtained, S3 client ready");
            operation.completion().complete(null);
        } catch (RuntimeException authenticationFailure) {
            operation.completion().completeExceptionally(authenticationFailure);
        }
    }

    private boolean applyAuthentication(
            SsoAuthFlow.AuthenticationResult result,
            S3ConnectionConfig config,
            long operation,
            LoginOperation expectedLogin) {
        synchronized (refreshLock) {
            if (operation != authenticationGeneration
                    || (expectedLogin != null && activeLogin != expectedLogin)) {
                logger.debug("Ignoring credentials from a superseded authentication action");
                return false;
            }

            S3Client newClient = buildS3Client(result.credentials().credentials(), config);
            try {
                persistSession(config, result.token());
            } catch (RuntimeException failure) {
                closeClient(newClient, "failed authentication");
                if (activeLogin == expectedLogin) {
                    activeLogin = null;
                }
                throw failure;
            }

            State old = state;
            closeClient(old.s3Client(), "authentication replacement");
            state =
                    new State(
                            newClient,
                            result.credentials().credentials(),
                            result.credentials().expiresAtEpochSec(),
                            config);
            if (activeLogin == expectedLogin) {
                activeLogin = null;
            }
            return true;
        }
    }

    private void persistSession(S3ConnectionConfig config, SsoTokenData token) {
        try {
            store.saveSsoToken(token);
            store.saveConfig(config);
        } catch (IOException failure) {
            try {
                // Config writes are atomic, so a failed replacement keeps the previous fields.
                // Remove any newly saved token; it must not restore an incomplete login.
                store.clearSsoToken();
            } catch (IOException clearFailure) {
                failure.addSuppressed(clearFailure);
            }
            throw new IllegalStateException("Failed to save AWS session", failure);
        }
    }

    private static void cancelOperation(LoginOperation operation) {
        if (operation == null) {
            return;
        }
        operation.flowAttempt().cancel();
        operation.completion().cancel(false);
    }

    private static void closeClient(S3Client client, String reason) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception failure) {
            logger.warn("Failed to close S3 client during {}: {}", reason, failure.getMessage());
        }
    }

    private static S3Client buildS3Client(AwsCredentials creds, S3ConnectionConfig config) {
        return SsoAuthFlow.withExtensionClassLoader(
                () -> {
                    var builder = S3Client.builder();
                    builder.credentialsProvider(StaticCredentialsProvider.create(creds));
                    builder.region(Region.of(config.ssoRegion()));
                    builder.overrideConfiguration(
                            options -> options.retryStrategy(RetryMode.STANDARD));
                    return builder.build();
                });
    }
}
