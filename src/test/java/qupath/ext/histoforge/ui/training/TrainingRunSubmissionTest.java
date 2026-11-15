package qupath.ext.histoforge.ui.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Code;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunAccepted;
import qupath.ext.histoforge.training.TrainingFormFixtures;

class TrainingRunSubmissionTest {
    @Test
    void onlyKnownPreAcceptanceErrorsProveRejection() {
        var definite =
                EnumSet.of(
                        Code.RATE_LIMITED,
                        Code.MODULE_NOT_FOUND,
                        Code.MODULE_IDENTITY_MISMATCH,
                        Code.TRAINING_DATASET_NOT_FOUND);
        for (Code code : Code.values()) {
            var error =
                    new InferenceException(
                            GatewayErrorCodec.httpStatus(code),
                            new GatewayErrorCodec.Error(
                                    1, code, "reason", Optional.empty(), Optional.empty()));
            assertEquals(
                    definite.contains(code),
                    TrainingRunSubmission.definitelyRejected(error),
                    code.name());
        }
        assertFalse(
                TrainingRunSubmission.definitelyRejected(
                        new GatewayTransport.DeploymentChangedException(
                                "changed during token refresh")));
        assertFalse(TrainingRunSubmission.definitelyRejected(new IOException("lost response")));
        assertFalse(TrainingRunSubmission.definitelyRejected(new InterruptedException()));
        assertFalse(
                TrainingRunSubmission.definitelyRejected(
                        new InferenceException(422, "untyped error")));
        assertFalse(
                TrainingRunSubmission.definitelyRejected(
                        new InferenceException("invalid response")));
    }

    @Test
    void providerCreditFailureShowsSafeGuidanceWithoutCodeOrReplayAdviceLoss() {
        String guidance =
                "The Modal workspace does not have enough credit to start training. "
                        + "Add credit or switch to another workspace with available credit.";
        var error =
                new InferenceException(
                        402,
                        new GatewayErrorCodec.Error(
                                1,
                                Code.PROVIDER_CREDIT_EXHAUSTED,
                                guidance,
                                Optional.of("44444444-4444-4444-8444-444444444444"),
                                Optional.empty()));

        assertFalse(TrainingRunSubmission.definitelyRejected(error));
        assertEquals(
                guidance
                        + "\n\nThe training request may already have been accepted. "
                        + "Check Jobs before starting another run.",
                TrainingRunSubmission.unconfirmedFailureMessage(error));
        assertFalse(
                TrainingRunSubmission.unconfirmedFailureMessage(error)
                        .contains("PROVIDER_CREDIT_EXHAUSTED"));
    }

    @Test
    @Timeout(5)
    void postsFrozenRequestOnceAndKeepsBusyUntilUiReceivesAcceptance() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var states = new ArrayList<Boolean>();
        var client = client();
        var request = request();
        var accepted =
                new TrainingRunAccepted(
                        "44444444-4444-4444-8444-444444444444",
                        TrainingRunRequestCodec.digest(request, limits()));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.submit(request))
                .thenAnswer(
                        call -> {
                            assertSame(request, call.getArgument(0));
                            entered.countDown();
                            release.await();
                            return accepted;
                        });
        var result = new AtomicReference<TrainingRunAccepted>();
        var submission = new TrainingRunSubmission(states::add, callbacks::add);
        submission.start(
                client,
                request,
                result::set,
                error -> {
                    throw new AssertionError(error);
                });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        submission.start(
                client,
                request,
                result::set,
                error -> {
                    throw new AssertionError(error);
                });
        assertTrue(submission.isRunning());
        var settled = submission.awaitSubmission();
        assertFalse(settled.isDone());
        release.countDown();
        Runnable response = take(callbacks);
        assertTrue(submission.isRunning());
        assertFalse(settled.isDone());
        response.run();
        assertFalse(submission.isRunning());
        assertTrue(settled.isDone());
        assertSame(accepted, result.get());
        assertEquals(List.of(true, false), states);
        verify(client, times(1)).submit(request);
    }

    @Test
    @Timeout(5)
    void allUnconfirmedTransportAndResponseFailuresReturnWithoutReplay() throws Exception {
        for (Exception error :
                List.of(
                        new IOException("response lost"),
                        new InterruptedException("connection interrupted"),
                        new InferenceException(409, "attachment conflicted"),
                        new InferenceException(503, "dispatch unavailable"),
                        new InferenceException("malformed acceptance"))) {
            var callbacks = new LinkedBlockingQueue<Runnable>();
            var client = client();
            var request = request();
            when(client.submit(request)).thenThrow(error);
            var failure = new AtomicReference<Throwable>();
            var submission = new TrainingRunSubmission(ignored -> {}, callbacks::add);
            submission.start(
                    client,
                    request,
                    value -> {
                        throw new AssertionError("unexpected acceptance");
                    },
                    failure::set);
            take(callbacks).run();
            assertSame(error, failure.get());
            assertFalse(submission.isRunning());
            assertTrue(submission.awaitSubmission().isDone());
            verify(client, times(1)).submit(request);
        }
    }

    @Test
    @Timeout(5)
    void cancellationAfterUploadButBeforeUiHandoffDoesNotPost() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var local = new TrainingOperationRunner(ignored -> {}, callbacks::add);
        var submitted = new AtomicReference<TrainingRunRequest>();
        var request = request();
        local.start(
                (cancelled, progress) -> request,
                ignored -> {},
                submitted::set,
                error -> {
                    throw new AssertionError(error);
                });
        Runnable completedUpload = take(callbacks);
        local.cancel();
        completedUpload.run();
        assertEquals(null, submitted.get());
        assertFalse(local.isRunning());
    }

    @Test
    @Timeout(5)
    void localCancelAfterHandoffCannotInterruptThePost() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var client = client();
        var request = request();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.submit(request))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                            assertFalse(Thread.currentThread().isInterrupted());
                            return new TrainingRunAccepted(
                                    "44444444-4444-4444-8444-444444444444",
                                    TrainingRunRequestCodec.digest(request, limits()));
                        });
        var local = new TrainingOperationRunner(ignored -> {}, callbacks::add);
        var submission = new TrainingRunSubmission(ignored -> {}, callbacks::add);
        local.start(
                (cancelled, progress) -> request,
                ignored -> {},
                value ->
                        submission.start(
                                client,
                                value,
                                accepted -> {},
                                error -> {
                                    throw new AssertionError(error);
                                }),
                error -> {
                    throw new AssertionError(error);
                });
        take(callbacks).run();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        local.cancel();
        assertTrue(submission.isRunning());
        release.countDown();
        take(callbacks).run();
        assertFalse(submission.isRunning());
    }

    private static Runnable take(BlockingQueue<Runnable> callbacks) throws InterruptedException {
        Runnable callback = callbacks.poll(2, TimeUnit.SECONDS);
        if (callback == null) throw new AssertionError("UI callback did not arrive");
        return callback;
    }

    private static TrainingRunGatewayClient client() throws Exception {
        var client = mock(TrainingRunGatewayClient.class);
        when(client.limits()).thenReturn(limits());
        return client;
    }

    private static TrainingApiLimits limits() throws Exception {
        return TrainingLimitsCodec.parse(
                TrainingFormFixtures.fixture("training-datasets-v1.json").get("trainingApiLimits"));
    }

    private static TrainingRunRequest request() throws Exception {
        return TrainingRunRequestCodec.parse(
                TrainingFormFixtures.fixture("training-datasets-v1.json")
                        .getAsJsonObject("runRequest")
                        .get("value"),
                limits());
    }
}
