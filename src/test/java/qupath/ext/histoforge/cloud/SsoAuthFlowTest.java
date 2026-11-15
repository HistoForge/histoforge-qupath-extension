package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

class SsoAuthFlowTest {

    @Test
    void cancellationTargetsOnlyItsOwnLoginAttempt() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CompletableFuture<SsoAuthFlow.AuthenticationResult> firstCompletion =
                new CompletableFuture<>();
        CompletableFuture<SsoAuthFlow.AuthenticationResult> secondCompletion =
                new CompletableFuture<>();
        SsoAuthFlow.AuthenticationResult secondResult = result("second");
        Thread firstThread =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        completeAfterRelease(
                                                firstCompletion,
                                                firstStarted,
                                                releaseFirst,
                                                firstInterrupted,
                                                result("first")));
        Thread secondThread =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        completeAfterRelease(
                                                secondCompletion,
                                                secondStarted,
                                                releaseSecond,
                                                new CountDownLatch(1),
                                                secondResult));
        SsoAuthFlow.LoginAttempt first = new SsoAuthFlow.LoginAttempt(firstCompletion, firstThread);
        SsoAuthFlow.LoginAttempt second =
                new SsoAuthFlow.LoginAttempt(secondCompletion, secondThread);
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

            first.cancel();

            assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS));
            assertTrue(first.completion().isCancelled());
            assertFalse(second.completion().isDone());

            releaseSecond.countDown();
            assertEquals(secondResult, second.completion().get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            first.cancel();
            second.cancel();
        }
    }

    private static void completeAfterRelease(
            CompletableFuture<SsoAuthFlow.AuthenticationResult> completion,
            CountDownLatch started,
            CountDownLatch release,
            CountDownLatch interrupted,
            SsoAuthFlow.AuthenticationResult result) {
        started.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test login was not released");
            }
            completion.complete(result);
        } catch (InterruptedException failure) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            completion.completeExceptionally(new CancellationException("test login interrupted"));
        }
    }

    private static SsoAuthFlow.AuthenticationResult result(String identity) {
        return new SsoAuthFlow.AuthenticationResult(
                new AwsCredentialData(
                        AwsBasicCredentials.create(identity + "-access", identity + "-secret"),
                        Instant.now().plusSeconds(3600).getEpochSecond()),
                new SsoTokenData(
                        identity + "-access-token",
                        identity + "-refresh-token",
                        Instant.now().plusSeconds(7200).getEpochSecond(),
                        identity + "-client",
                        identity + "-client-secret"));
    }
}
