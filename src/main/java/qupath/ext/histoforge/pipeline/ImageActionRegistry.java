package qupath.ext.histoforge.pipeline;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local mutation reservation and short-lived image lease for one project image. */
public final class ImageActionRegistry {

    private final ConcurrentMap<String, ImageState> images = new ConcurrentHashMap<>();

    /**
     * Acquire exclusive HistoForge access to one project image.
     *
     * <p>The wait is interruptible so cancellation can stop before any image state is captured.
     * Different image IDs do not block one another. Lease entries live only as long as this
     * application-scoped registry and are bounded by the images touched in that process.
     */
    public Lease acquire(String imageId) throws InterruptedException {
        Reservation reservation = reserve(imageId);
        try {
            return reservation.acquireLease(true);
        } catch (InterruptedException | RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    /**
     * Acquire only the short-lived image-access lease, without reserving a future mutation.
     *
     * <p>Read-only captures use this path so they may proceed while a recoverable action retains
     * mutation ownership but is waiting for backend work. They still serialize with that action's
     * capture and final commit.
     */
    public Lease acquireLease(String imageId) throws InterruptedException {
        String key = requireImageId(imageId);
        ImageState state = images.computeIfAbsent(key, ignored -> new ImageState());
        state.leases.acquire();
        return new Lease(key, state, null, false);
    }

    /**
     * Reserve the image for one HistoForge mutating action without holding its image lease.
     *
     * <p>A recoverable action keeps this reservation while backend work runs, but acquires the
     * short-lived lease only while capturing or committing QuPath state. This prevents another
     * HistoForge mutation from racing the action without pretending to lock user edits.
     */
    public Reservation reserve(String imageId) throws InterruptedException {
        String key = requireImageId(imageId);
        ImageState state = images.computeIfAbsent(key, ignored -> new ImageState());
        state.mutations.acquire();
        return new Reservation(key, state);
    }

    private static String requireImageId(String imageId) {
        String value = Objects.requireNonNull(imageId, "imageId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("imageId must be nonblank");
        }
        return value;
    }

    /** One long-lived HistoForge mutation reservation that may reacquire a short image lease. */
    public static final class Reservation implements AutoCloseable {

        private final String imageId;
        private final ImageState state;
        private boolean activeLease;
        private boolean closed;

        private Reservation(String imageId, ImageState state) {
            this.imageId = imageId;
            this.state = state;
        }

        public String imageId() {
            return imageId;
        }

        /** Acquire exclusive HistoForge access while this reservation remains active. */
        public Lease acquireLease() throws InterruptedException {
            return acquireLease(false);
        }

        private synchronized Lease acquireLease(boolean closesReservation)
                throws InterruptedException {
            if (closed) {
                throw new IllegalStateException("image reservation is already closed");
            }
            if (activeLease) {
                throw new IllegalStateException("image reservation already owns a lease");
            }
            state.leases.acquire();
            activeLease = true;
            return new Lease(imageId, state, this, closesReservation);
        }

        private synchronized void releaseLease() {
            if (!activeLease) {
                throw new IllegalStateException("image reservation has no active lease");
            }
            activeLease = false;
            state.leases.release();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                throw new IllegalStateException("image reservation is already closed");
            }
            if (activeLease) {
                throw new IllegalStateException(
                        "image reservation cannot close while its lease is active");
            }
            closed = true;
            state.mutations.release();
        }
    }

    /** One exactly-once releasable short-lived image lease. */
    public static final class Lease implements AutoCloseable {

        private final String imageId;
        private final ImageState state;
        private final Reservation reservation;
        private final boolean closesReservation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(
                String imageId,
                ImageState state,
                Reservation reservation,
                boolean closesReservation) {
            this.imageId = imageId;
            this.state = state;
            this.reservation = reservation;
            this.closesReservation = closesReservation;
        }

        public String imageId() {
            return imageId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("image lease is already closed");
            }
            if (reservation == null) {
                state.leases.release();
            } else {
                reservation.releaseLease();
                if (closesReservation) {
                    reservation.close();
                }
            }
        }
    }

    private static final class ImageState {
        private final Semaphore mutations = new Semaphore(1, true);
        private final Semaphore leases = new Semaphore(1, true);
    }
}
