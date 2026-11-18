package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sso.SsoClient;
import software.amazon.awssdk.services.sso.model.*;
import software.amazon.awssdk.services.ssooidc.SsoOidcClient;
import software.amazon.awssdk.services.ssooidc.model.*;

class SsoProtocolTest {
    @Test
    void pendingAndSlowDownHonorIntervalsThenExchangeFirstAccountAndRole() throws Exception {
        var flow = new Flow();
        when(flow.oidc.createToken(any(CreateTokenRequest.class)))
                .thenThrow(AuthorizationPendingException.builder().build())
                .thenThrow(SlowDownException.builder().build())
                .thenReturn(token());
        var result =
                flow.login("https://example.test/start", "us-east-1")
                        .completion()
                        .get(5, TimeUnit.SECONDS);
        assertEquals(List.of(2, 2, 7), flow.waits);
        assertEquals(URI.create("https://example.test/approve"), flow.browser);
        assertEquals("key", result.credentials().credentials().accessKeyId());
        assertEquals(7000, result.credentials().expiresAtEpochSec());
        assertEquals(1111, result.token().expiresAtEpochSec());
        verify(flow.sso)
                .getRoleCredentials(
                        GetRoleCredentialsRequest.builder()
                                .accessToken("access")
                                .accountId("first")
                                .roleName("first-role")
                                .build());
        verify(flow.store, never()).saveSsoToken(any());
        verify(flow.oidc).close();
        verify(flow.sso).close();
    }

    @Test
    void expiryAndTimeoutRemainFailuresAndCloseClients() throws Exception {
        var expired = new Flow();
        when(expired.oidc.createToken(any(CreateTokenRequest.class)))
                .thenThrow(ExpiredTokenException.builder().build());
        var error =
                assertThrows(
                        ExecutionException.class,
                        () -> expired.login("url", "region").completion().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("expired"));
        var timeout = new Flow();
        when(timeout.oidc.startDeviceAuthorization(any(StartDeviceAuthorizationRequest.class)))
                .thenReturn(authorization(3));
        when(timeout.oidc.createToken(any(CreateTokenRequest.class)))
                .thenThrow(AuthorizationPendingException.builder().build());
        error =
                assertThrows(
                        ExecutionException.class,
                        () -> timeout.login("url", "region").completion().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("timed out"));
        verify(timeout.oidc, times(1)).createToken(any(CreateTokenRequest.class));
        verify(timeout.oidc).close();
        verify(timeout.sso).close();
    }

    @Test
    void restoreRefreshesExpiredTokenWithoutWritingIt() throws Exception {
        var flow = new Flow();
        when(flow.store.loadSsoToken())
                .thenReturn(
                        Optional.of(new SsoTokenData("old", "refresh", 999, "client", "secret")));
        when(flow.oidc.createToken(any(CreateTokenRequest.class))).thenReturn(token());
        assertEquals("access", flow.tryRestore("region").orElseThrow().token().accessToken());
        verify(flow.oidc)
                .createToken(
                        CreateTokenRequest.builder()
                                .clientId("client")
                                .clientSecret("secret")
                                .grantType("refresh_token")
                                .refreshToken("refresh")
                                .build());
        verify(flow.store, never()).saveSsoToken(any());
        assertNull(flow.browser);
    }

    @Test
    void missingAccountsRolesAndRefreshTokenHaveExplicitFailures() throws Exception {
        var accounts = new Flow();
        when(accounts.oidc.createToken(any(CreateTokenRequest.class))).thenReturn(token());
        when(accounts.sso.listAccounts(any(ListAccountsRequest.class)))
                .thenReturn(ListAccountsResponse.builder().build());
        var error =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                accounts.login("url", "region")
                                        .completion()
                                        .get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("No AWS accounts"));
        var roles = new Flow();
        when(roles.oidc.createToken(any(CreateTokenRequest.class))).thenReturn(token());
        when(roles.sso.listAccountRoles(any(ListAccountRolesRequest.class)))
                .thenReturn(ListAccountRolesResponse.builder().build());
        error =
                assertThrows(
                        ExecutionException.class,
                        () -> roles.login("url", "region").completion().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("No roles"));
        when(roles.store.loadSsoToken())
                .thenReturn(Optional.of(new SsoTokenData("old", null, 999, "client", "secret")));
        assertTrue(roles.tryRestore("region").isEmpty());
    }

    private static CreateTokenResponse token() {
        return CreateTokenResponse.builder()
                .accessToken("access")
                .refreshToken("refresh")
                .expiresIn(100)
                .build();
    }

    private static StartDeviceAuthorizationResponse authorization(int seconds) {
        return StartDeviceAuthorizationResponse.builder()
                .deviceCode("device")
                .verificationUriComplete("https://example.test/approve")
                .interval(2)
                .expiresIn(seconds)
                .build();
    }

    private static final class Flow extends SsoAuthFlow {
        final SsoOidcClient oidc = mock(SsoOidcClient.class);
        final SsoClient sso = mock(SsoClient.class);
        final S3CredentialStore store;
        final List<Integer> waits = new ArrayList<>();
        long time = 1000;
        URI browser;

        Flow() {
            this(mock(S3CredentialStore.class));
        }

        Flow(S3CredentialStore store) {
            super(store);
            this.store = store;
            when(oidc.registerClient(any(RegisterClientRequest.class)))
                    .thenReturn(
                            RegisterClientResponse.builder()
                                    .clientId("client")
                                    .clientSecret("secret")
                                    .build());
            when(oidc.startDeviceAuthorization(any(StartDeviceAuthorizationRequest.class)))
                    .thenReturn(authorization(100));
            when(sso.listAccounts(any(ListAccountsRequest.class)))
                    .thenReturn(
                            ListAccountsResponse.builder()
                                    .accountList(
                                            AccountInfo.builder()
                                                    .accountId("first")
                                                    .accountName("One")
                                                    .build(),
                                            AccountInfo.builder().accountId("second").build())
                                    .build());
            when(sso.listAccountRoles(any(ListAccountRolesRequest.class)))
                    .thenReturn(
                            ListAccountRolesResponse.builder()
                                    .roleList(
                                            RoleInfo.builder().roleName("first-role").build(),
                                            RoleInfo.builder().roleName("second-role").build())
                                    .build());
            when(sso.getRoleCredentials(any(GetRoleCredentialsRequest.class)))
                    .thenReturn(
                            GetRoleCredentialsResponse.builder()
                                    .roleCredentials(
                                            RoleCredentials.builder()
                                                    .accessKeyId("key")
                                                    .secretAccessKey("secret")
                                                    .sessionToken("session")
                                                    .expiration(7_000_000L)
                                                    .build())
                                    .build());
        }

        @Override
        long nowSeconds() {
            return time;
        }

        @Override
        void pauseSeconds(int seconds) {
            waits.add(seconds);
            time += seconds;
        }

        @Override
        void openBrowser(URI uri) {
            browser = uri;
        }

        @Override
        SsoOidcClient oidcClient(String region) {
            return oidc;
        }

        @Override
        SsoClient ssoClient(String region) {
            return sso;
        }
    }
}
