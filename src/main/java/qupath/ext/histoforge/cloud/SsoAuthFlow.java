package qupath.ext.histoforge.cloud;

import java.awt.Desktop;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sso.SsoClient;
import software.amazon.awssdk.services.sso.model.*;
import software.amazon.awssdk.services.ssooidc.SsoOidcClient;
import software.amazon.awssdk.services.ssooidc.model.*;

/** Exchanges an IAM Identity Center device login for the first available AWS account and role. */
public class SsoAuthFlow {
    private static final Logger logger = LoggerFactory.getLogger(SsoAuthFlow.class);
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private final S3CredentialStore store;

    record AuthenticationResult(AwsCredentialData credentials, SsoTokenData token) {}

    /** Cancellation belongs to this login; it cannot interrupt a later attempt. */
    record LoginAttempt(CompletableFuture<AuthenticationResult> completion, Thread thread) {
        void cancel() {
            completion.cancel(false);
            thread.interrupt();
        }
    }

    public SsoAuthFlow(S3CredentialStore store) {
        this.store = store;
    }

    LoginAttempt login(String startUrl, String region) {
        var result = new CompletableFuture<AuthenticationResult>();
        var thread =
                Thread.ofVirtual()
                        .name("histoforge-sso-login")
                        .start(
                                () -> {
                                    try {
                                        result.complete(authenticate(startUrl, region));
                                    } catch (Throwable failure) {
                                        result.completeExceptionally(failure);
                                    }
                                });
        return new LoginAttempt(result, thread);
    }

    Optional<AuthenticationResult> tryRestore(String region) {
        return store.loadSsoToken()
                .flatMap(
                        saved -> {
                            try (var oidc = oidcClient(region);
                                    var sso = ssoClient(region)) {
                                SsoTokenData current = saved;
                                if (nowSeconds() >= saved.expiresAtEpochSec()) {
                                    if (saved.refreshToken() == null) return Optional.empty();
                                    var refreshed =
                                            oidc.createToken(
                                                    CreateTokenRequest.builder()
                                                            .grantType("refresh_token")
                                                            .refreshToken(saved.refreshToken())
                                                            .clientId(saved.clientId())
                                                            .clientSecret(saved.clientSecret())
                                                            .build());
                                    current =
                                            cachedToken(
                                                    refreshed,
                                                    saved.clientId(),
                                                    saved.clientSecret());
                                }
                                return Optional.of(
                                        new AuthenticationResult(
                                                roleCredentials(sso, current.accessToken()),
                                                current));
                            } catch (Exception failure) {
                                logger.warn(
                                        "Failed to restore SSO session: {}", failure.getMessage());
                                return Optional.empty();
                            }
                        });
    }

    private AuthenticationResult authenticate(String startUrl, String region) {
        try (var oidc = oidcClient(region);
                var sso = ssoClient(region)) {
            var registration =
                    oidc.registerClient(
                            RegisterClientRequest.builder()
                                    .clientName("histoforge-qupath")
                                    .clientType("public")
                                    .build());
            var authorization =
                    oidc.startDeviceAuthorization(
                            StartDeviceAuthorizationRequest.builder()
                                    .startUrl(startUrl)
                                    .clientId(registration.clientId())
                                    .clientSecret(registration.clientSecret())
                                    .build());
            var request =
                    CreateTokenRequest.builder()
                            .grantType(DEVICE_GRANT)
                            .clientId(registration.clientId())
                            .clientSecret(registration.clientSecret())
                            .deviceCode(authorization.deviceCode())
                            .build();
            // Count the device-code lifetime from authorization, including time opening the
            // browser.
            long deadline = nowSeconds() + authorization.expiresIn();
            openBrowser(URI.create(authorization.verificationUriComplete()));
            int interval = authorization.interval() == null ? 5 : authorization.interval();
            var response = awaitToken(oidc, request, interval, deadline);
            var token = cachedToken(response, registration.clientId(), registration.clientSecret());
            return new AuthenticationResult(roleCredentials(sso, token.accessToken()), token);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("SSO login interrupted");
        } catch (CancellationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("SSO login failed: " + failure.getMessage(), failure);
        }
    }

    private CreateTokenResponse awaitToken(
            SsoOidcClient oidc, CreateTokenRequest request, int interval, long deadline)
            throws InterruptedException {
        int delay = interval;
        while (nowSeconds() < deadline) {
            pauseSeconds(delay);
            if (nowSeconds() >= deadline) break;
            try {
                return oidc.createToken(request);
            } catch (AuthorizationPendingException pending) {
                /* The browser approval is still pending. */
            } catch (SlowDownException slower) {
                delay += 5;
            } catch (ExpiredTokenException expired) {
                throw new IllegalStateException("SSO login expired — user took too long", expired);
            }
        }
        throw new IllegalStateException("SSO login timed out");
    }

    private static AwsCredentialData roleCredentials(SsoClient sso, String token) {
        var accounts =
                sso.listAccounts(ListAccountsRequest.builder().accessToken(token).build())
                        .accountList();
        if (accounts.isEmpty())
            throw new IllegalStateException("No AWS accounts found for this SSO user");
        var account = accounts.getFirst();
        var roles =
                sso.listAccountRoles(
                                ListAccountRolesRequest.builder()
                                        .accessToken(token)
                                        .accountId(account.accountId())
                                        .build())
                        .roleList();
        if (roles.isEmpty())
            throw new IllegalStateException("No roles found for account " + account.accountName());
        var credentials =
                sso.getRoleCredentials(
                                GetRoleCredentialsRequest.builder()
                                        .accessToken(token)
                                        .accountId(account.accountId())
                                        .roleName(roles.getFirst().roleName())
                                        .build())
                        .roleCredentials();
        return new AwsCredentialData(
                AwsSessionCredentials.create(
                        credentials.accessKeyId(),
                        credentials.secretAccessKey(),
                        credentials.sessionToken()),
                credentials.expiration() / 1000);
    }

    private SsoTokenData cachedToken(CreateTokenResponse response, String client, String secret) {
        return new SsoTokenData(
                response.accessToken(),
                response.refreshToken(),
                nowSeconds() + response.expiresIn(),
                client,
                secret);
    }

    // These IO boundaries allow protocol tests without credentials, browser access or real waits.
    long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    void pauseSeconds(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }

    void openBrowser(URI uri) throws Exception {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            throw new IllegalStateException("Cannot open browser. Visit this URL manually: " + uri);
        Desktop.getDesktop().browse(uri);
    }

    SsoOidcClient oidcClient(String region) {
        return withExtensionClassLoader(
                () -> SsoOidcClient.builder().region(Region.of(region)).build());
    }

    SsoClient ssoClient(String region) {
        return withExtensionClassLoader(
                () -> SsoClient.builder().region(Region.of(region)).build());
    }

    /** SDK ServiceLoader lookups must see the HTTP transport bundled in the extension JAR. */
    static <T> T withExtensionClassLoader(java.util.function.Supplier<T> supplier) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(SsoAuthFlow.class.getClassLoader());
        try {
            return supplier.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
