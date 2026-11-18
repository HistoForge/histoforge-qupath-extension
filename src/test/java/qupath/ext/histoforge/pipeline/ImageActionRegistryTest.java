package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ImageActionRegistryTest {

    @Test
    void sameImageWaitsUntilLeaseClosesWhileAnotherImageRemainsIndependent() throws Exception {
        ImageActionRegistry registry = new ImageActionRegistry();
        var first = registry.acquire("image-a");
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch waiterStarted = new CountDownLatch(1);
            var sameImage =
                    executor.submit(
                            () -> {
                                waiterStarted.countDown();
                                return registry.acquire("image-a");
                            });
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> sameImage.get(100, TimeUnit.MILLISECONDS));

            var otherImage = executor.submit(() -> registry.acquire("image-b"));
            try (var other = otherImage.get(5, TimeUnit.SECONDS)) {
                assertEquals("image-b", other.imageId());
            }

            first.close();
            try (var second = sameImage.get(5, TimeUnit.SECONDS)) {
                assertEquals("image-a", second.imageId());
            }
        }
    }

    @Test
    void acquisitionIsInterruptibleAndLeaseCannotReleaseTwice() throws Exception {
        ImageActionRegistry registry = new ImageActionRegistry();
        try (var first = registry.acquire("image-a")) {
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread waiter =
                    new Thread(
                            () -> {
                                try {
                                    registry.acquire("image-a");
                                    unexpected.set(
                                            new AssertionError(
                                                    "interrupted acquisition succeeded"));
                                } catch (InterruptedException expected) {
                                    // Expected: cancellation stops before the lease is acquired.
                                } catch (Throwable failure) {
                                    unexpected.set(failure);
                                }
                            });
            waiter.start();
            Thread.sleep(Duration.ofMillis(50));
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(5));
            assertFalse(waiter.isAlive());
            assertNull(unexpected.get());
        }

        var lease = registry.acquire("image-a");
        lease.close();
        assertThrows(IllegalStateException.class, lease::close);
    }

    @Test
    void recoverableReservationAllowsReadOnlyCaptureButRetainsMutationOwnership() throws Exception {
        ImageActionRegistry registry = new ImageActionRegistry();
        var reservation = registry.reserve("image-a");
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<ImageActionRegistry.Lease> readOnly;
            try (var firstLease = reservation.acquireLease()) {
                assertEquals("image-a", firstLease.imageId());
                assertThrows(IllegalStateException.class, reservation::acquireLease);
                assertThrows(IllegalStateException.class, reservation::close);
                readOnly = executor.submit(() -> registry.acquireLease("image-a"));
                assertThrows(
                        TimeoutException.class, () -> readOnly.get(100, TimeUnit.MILLISECONDS));
            }

            java.util.concurrent.Future<ImageActionRegistry.Lease> finalCommit;
            try (var readLease = readOnly.get(5, TimeUnit.SECONDS)) {
                assertEquals("image-a", readLease.imageId());
                finalCommit = executor.submit(reservation::acquireLease);
                assertThrows(
                        TimeoutException.class, () -> finalCommit.get(100, TimeUnit.MILLISECONDS));
            }
            try (var secondLease = finalCommit.get(5, TimeUnit.SECONDS)) {
                assertEquals("image-a", secondLease.imageId());
            }

            var competing = executor.submit(() -> registry.acquire("image-a"));
            assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
            reservation.close();
            try (var acquired = competing.get(5, TimeUnit.SECONDS)) {
                assertEquals("image-a", acquired.imageId());
            }
        }
        assertThrows(IllegalStateException.class, reservation::close);
    }

    @Test
    void imageIdentityMustBePresent() {
        ImageActionRegistry registry = new ImageActionRegistry();
        assertThrows(NullPointerException.class, () -> registry.acquire(null));
        assertThrows(IllegalArgumentException.class, () -> registry.acquire("  "));
        assertThrows(NullPointerException.class, () -> registry.acquireLease(null));
        assertThrows(IllegalArgumentException.class, () -> registry.acquireLease("  "));
    }
}
