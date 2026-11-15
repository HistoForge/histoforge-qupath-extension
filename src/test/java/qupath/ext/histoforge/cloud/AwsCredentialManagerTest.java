package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.services.s3.S3Client;

class AwsCredentialManagerTest {

    private static final S3ConnectionConfig SAVED_CONFIG =
            new S3ConnectionConfig(
                    "https://saved.awsapps.com/start", "us-east-1", "saved-slides", null);
    private static final S3ConnectionConfig LOGIN_CONFIG =
            new S3ConnectionConfig(
                    "https://login.awsapps.com/start", "us-west-2", "login-slides", "study/");

    @Test
    void explicitLoginSupersedesBackgroundRestoreInMemoryAndOnDisk(@TempDir Path dir)
            throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch finishRestore = new CountDownLatch(1);
        SsoTokenData savedToken = token("saved");
        SsoTokenData restoredToken = token("restored");
        SsoTokenData loginToken = token("login");
        store.saveConfig(SAVED_CONFIG);
        store.saveSsoToken(savedToken);
        when(flow.tryRestore(SAVED_CONFIG.ssoRegion()))
                .thenAnswer(
                        invocation -> {
                            restoreStarted.countDown();
                            assertTrue(finishRestore.await(5, TimeUnit.SECONDS));
                            return Optional.of(result("restored", restoredToken));
                        });
        doReturn(
                        loginAttempt(
                                CompletableFuture.completedFuture(result("login", loginToken)),
                                new AtomicBoolean()))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        CompletableFuture<Boolean> restore = manager.restoreSavedSession();
        try {
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS));
            manager.login(LOGIN_CONFIG).join();
            S3Client loginClient = manager.getS3Client();

            finishRestore.countDown();

            assertFalse(restore.join());
            assertEquals(LOGIN_CONFIG, manager.getCurrentConfig());
            assertSame(loginClient, manager.getS3Client());
            assertEquals(LOGIN_CONFIG, store.loadConfig().orElseThrow());
            assertEquals(loginToken, store.loadSsoToken().orElseThrow());
        } finally {
            finishRestore.countDown();
            manager.invalidate();
        }
    }

    @Test
    void newerLoginSupersedesEarlierLoginAndOwnsDurableSession(@TempDir Path dir) {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        CompletableFuture<SsoAuthFlow.AuthenticationResult> firstResult = new CompletableFuture<>();
        CompletableFuture<SsoAuthFlow.AuthenticationResult> secondResult =
                new CompletableFuture<>();
        AtomicBoolean firstCancelled = new AtomicBoolean();
        AtomicBoolean secondCancelled = new AtomicBoolean();
        SsoTokenData firstToken = token("first");
        SsoTokenData secondToken = token("second");
        doReturn(loginAttempt(firstResult, firstCancelled))
                .when(flow)
                .login(SAVED_CONFIG.ssoStartUrl(), SAVED_CONFIG.ssoRegion());
        doReturn(loginAttempt(secondResult, secondCancelled))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        CompletableFuture<Void> firstLogin = manager.login(SAVED_CONFIG);
        CompletableFuture<Void> secondLogin = manager.login(LOGIN_CONFIG);
        try {
            assertTrue(firstCancelled.get());
            manager.cancelLogin(firstLogin);
            assertFalse(secondCancelled.get(), "a stale dialog must not cancel the newer login");

            secondResult.complete(result("second", secondToken));
            secondLogin.join();
            S3Client secondClient = manager.getS3Client();

            firstResult.complete(result("first", firstToken));

            assertThrows(CancellationException.class, firstLogin::join);
            assertEquals(LOGIN_CONFIG, manager.getCurrentConfig());
            assertSame(secondClient, manager.getS3Client());
            assertEquals(LOGIN_CONFIG, store.loadConfig().orElseThrow());
            assertEquals(secondToken, store.loadSsoToken().orElseThrow());
        } finally {
            manager.invalidate();
        }
    }

    @Test
    void logoutPreventsPendingLoginFromRecreatingDurableSession(@TempDir Path dir)
            throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        CompletableFuture<SsoAuthFlow.AuthenticationResult> pendingResult =
                new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        store.saveConfig(SAVED_CONFIG);
        store.saveSsoToken(token("saved"));
        doReturn(loginAttempt(pendingResult, cancelled))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        CompletableFuture<Void> login = manager.login(LOGIN_CONFIG);

        manager.logout();
        pendingResult.complete(result("late", token("late")));

        assertTrue(cancelled.get());
        assertThrows(CancellationException.class, login::join);
        assertFalse(manager.isAuthenticated());
        assertNull(manager.getS3Client());
        assertEquals(SAVED_CONFIG, store.loadConfig().orElseThrow());
        assertTrue(store.loadSsoToken().isEmpty());
    }

    @Test
    void logoutKeepsConnectionFieldsButRequiresFreshSignInAfterReopening(@TempDir Path dir)
            throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        doReturn(
                        loginAttempt(
                                CompletableFuture.completedFuture(result("login", token("login"))),
                                new AtomicBoolean()))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());
        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        manager.login(LOGIN_CONFIG).join();

        manager.logout();

        assertFalse(manager.isAuthenticated());
        assertNull(manager.getS3Client());
        assertEquals(LOGIN_CONFIG, manager.getCurrentConfig());
        assertEquals(LOGIN_CONFIG, store.loadConfig().orElseThrow());
        assertTrue(store.loadSsoToken().isEmpty());

        AwsCredentialManager reopened = new AwsCredentialManager(store);
        assertFalse(reopened.restoreSavedSession().get(5, TimeUnit.SECONDS));
        assertEquals(LOGIN_CONFIG, reopened.getCurrentConfig());
        assertNull(reopened.getS3Client());
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "config"})
    void failedReplacementLoginKeepsPreviousFieldsAndDiscardsNewToken(
            String failedWrite, @TempDir Path dir) throws Exception {
        S3CredentialStore store = spy(new S3CredentialStore(dir));
        store.saveConfig(SAVED_CONFIG);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        SsoTokenData newToken = token("new");
        doReturn(
                        loginAttempt(
                                CompletableFuture.completedFuture(result("new", newToken)),
                                new AtomicBoolean()))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());
        if (failedWrite.equals("token")) {
            doThrow(new IOException("token write failed")).when(store).saveSsoToken(newToken);
        } else {
            doThrow(new IOException("config write failed")).when(store).saveConfig(LOGIN_CONFIG);
        }
        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        assertFalse(manager.restoreSavedSession().get(5, TimeUnit.SECONDS));
        manager.logout();

        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> manager.login(LOGIN_CONFIG).join());

        assertFalse(manager.isAuthenticated());
        assertNull(manager.getS3Client());
        assertEquals(SAVED_CONFIG, manager.getCurrentConfig());
        assertEquals(SAVED_CONFIG, store.loadConfig().orElseThrow());
        assertTrue(store.loadSsoToken().isEmpty());
    }

    @Test
    void logoutFencesBackgroundRestoreAndKeepsFields(@TempDir Path dir) throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        store.saveConfig(SAVED_CONFIG);
        store.saveSsoToken(token("saved"));
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch finishRestore = new CountDownLatch(1);
        when(flow.tryRestore(SAVED_CONFIG.ssoRegion()))
                .thenAnswer(
                        invocation -> {
                            restoreStarted.countDown();
                            assertTrue(finishRestore.await(5, TimeUnit.SECONDS));
                            return Optional.of(result("late", token("late")));
                        });
        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        CompletableFuture<Boolean> restore = manager.restoreSavedSession();
        try {
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS));
            manager.logout();
            finishRestore.countDown();

            assertFalse(restore.get(5, TimeUnit.SECONDS));
            assertFalse(manager.isAuthenticated());
            assertNull(manager.getS3Client());
            assertEquals(SAVED_CONFIG, manager.getCurrentConfig());
            assertEquals(SAVED_CONFIG, store.loadConfig().orElseThrow());
            assertTrue(store.loadSsoToken().isEmpty());
        } finally {
            finishRestore.countDown();
        }
    }

    @Test
    void cancellationPreventsPendingLoginFromCreatingDurableSession(@TempDir Path dir) {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        CompletableFuture<SsoAuthFlow.AuthenticationResult> pendingResult =
                new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        doReturn(loginAttempt(pendingResult, cancelled))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        CompletableFuture<Void> login = manager.login(LOGIN_CONFIG);

        manager.cancelLogin(login);
        pendingResult.complete(result("late", token("late")));

        assertTrue(cancelled.get());
        assertThrows(CancellationException.class, login::join);
        assertFalse(manager.isAuthenticated());
        assertTrue(store.loadConfig().isEmpty());
        assertTrue(store.loadSsoToken().isEmpty());
    }

    @Test
    void expiryRefreshReplacesDurableTokenAndStabilizesNewCredentials(@TempDir Path dir) {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        SsoTokenData initialToken = token("initial");
        SsoTokenData refreshedToken = token("refreshed");
        doReturn(
                        loginAttempt(
                                CompletableFuture.completedFuture(
                                        result(
                                                "initial",
                                                initialToken,
                                                Instant.now().plusSeconds(60).getEpochSecond())),
                                new AtomicBoolean()))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());
        when(flow.tryRestore(LOGIN_CONFIG.ssoRegion()))
                .thenReturn(Optional.of(result("refreshed", refreshedToken)));

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        manager.login(LOGIN_CONFIG).join();
        try {
            S3Client refreshedClient = manager.getS3Client();

            assertTrue(manager.isAuthenticated());
            assertSame(refreshedClient, manager.getS3Client());
            assertEquals(LOGIN_CONFIG, store.loadConfig().orElseThrow());
            assertEquals(refreshedToken, store.loadSsoToken().orElseThrow());
            verify(flow, times(1)).tryRestore(LOGIN_CONFIG.ssoRegion());
        } finally {
            manager.invalidate();
        }
    }

    @Test
    void failedExpiryRefreshInvalidatesMemoryButPreservesDurableSession(@TempDir Path dir) {
        S3CredentialStore store = new S3CredentialStore(dir);
        SsoAuthFlow flow = mock(SsoAuthFlow.class);
        SsoTokenData initialToken = token("initial");
        doReturn(
                        loginAttempt(
                                CompletableFuture.completedFuture(
                                        result(
                                                "initial",
                                                initialToken,
                                                Instant.now().plusSeconds(60).getEpochSecond())),
                                new AtomicBoolean()))
                .when(flow)
                .login(LOGIN_CONFIG.ssoStartUrl(), LOGIN_CONFIG.ssoRegion());
        when(flow.tryRestore(LOGIN_CONFIG.ssoRegion())).thenReturn(Optional.empty());

        AwsCredentialManager manager = new AwsCredentialManager(store, flow);
        manager.login(LOGIN_CONFIG).join();

        assertNull(manager.getS3Client());
        assertFalse(manager.isAuthenticated());
        assertEquals(LOGIN_CONFIG, manager.getCurrentConfig());
        assertEquals(LOGIN_CONFIG, store.loadConfig().orElseThrow());
        assertEquals(initialToken, store.loadSsoToken().orElseThrow());
        verify(flow, times(1)).tryRestore(LOGIN_CONFIG.ssoRegion());
    }

    private static SsoAuthFlow.LoginAttempt loginAttempt(
            CompletableFuture<SsoAuthFlow.AuthenticationResult> completion,
            AtomicBoolean cancelled) {
        SsoAuthFlow.LoginAttempt attempt = mock(SsoAuthFlow.LoginAttempt.class);
        when(attempt.completion()).thenReturn(completion);
        doAnswer(
                        invocation -> {
                            cancelled.set(true);
                            return null;
                        })
                .when(attempt)
                .cancel();
        return attempt;
    }

    private static SsoAuthFlow.AuthenticationResult result(String identity, SsoTokenData token) {
        return new SsoAuthFlow.AuthenticationResult(credentials(identity), token);
    }

    private static SsoAuthFlow.AuthenticationResult result(
            String identity, SsoTokenData token, long expiresAtEpochSec) {
        return new SsoAuthFlow.AuthenticationResult(
                credentials(identity, expiresAtEpochSec), token);
    }

    private static AwsCredentialData credentials(String identity) {
        return credentials(identity, Instant.now().plusSeconds(3600).getEpochSecond());
    }

    private static AwsCredentialData credentials(String identity, long expiresAtEpochSec) {
        return new AwsCredentialData(
                AwsBasicCredentials.create(identity + "-access", identity + "-secret"),
                expiresAtEpochSec);
    }

    private static SsoTokenData token(String identity) {
        return new SsoTokenData(
                identity + "-access-token",
                identity + "-refresh-token",
                Instant.now().plusSeconds(7200).getEpochSecond(),
                identity + "-client",
                identity + "-secret");
    }
}
