package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.RecoverySourceFiles;

/**
 * Limits inference to two prepared images across the application, including unfinished uploads.
 * Ending an upload does not free capacity while its image remains on disk. Original slides and
 * training exports never enter this budget; owned original-file ZIPs do. Recovery records remain
 * the only persistent state.
 */
public final class PreparedImageBudget {
    static final PreparedImageBudget APPLICATION = new PreparedImageBudget();
    private static final int LIMIT = 2;
    private final Map<Path, Set<Path>> projects = new HashMap<>();
    private final Set<Path> directories = new LinkedHashSet<>();
    private final Set<Reservation> active = new LinkedHashSet<>();

    public static void observeRecoveryFiles(LargeActionHandleStore store) throws IOException {
        APPLICATION.register(store);
    }

    synchronized void register(LargeActionHandleStore store) throws IOException {
        var records = store.scanRecords();
        var observed = projects.computeIfAbsent(store.root(), ignored -> new LinkedHashSet<>());
        observed.addAll(RecoverySourceFiles.directories(store.root(), records.currentHandles()));
        records.historicalActions().stream()
                .flatMap(record -> record.preparedImageDirectories().stream())
                .map(store.root()::resolve)
                .forEach(observed::add);
    }

    /**
     * Reserve before creating a recovery record, so a full budget leaves no new unfinished action.
     */
    synchronized Reservation acquire(BooleanSupplier shouldStop)
            throws IOException, InterruptedException {
        while (true) {
            requireRunning(shouldStop);
            Set<Path> dormant = dormantImages();
            if (dormant.size() + active.size() < LIMIT) {
                var reservation = new Reservation();
                active.add(reservation);
                return reservation;
            }
            if (active.isEmpty())
                throw new IOException(
                        "Two prepared inference images are still retained for unfinished uploads. "
                                + "Continue or stop those analyses in Jobs (use the original client build for read-only history), then retry. Prepared files: "
                                + dormant);
            // Only an active preparation/upload can release capacity without another user action.
            // Wake periodically for cancellation and cleanup performed by the existing Jobs owner.
            wait(200);
        }
    }

    /** Resume existing bytes without charging a second slot, even when the budget is full. */
    synchronized Reservation resume(Path image) {
        Path path = image.toAbsolutePath().normalize();
        if (active.stream().anyMatch(owner -> path.equals(owner.image)))
            throw new IllegalStateException("Prepared image already has an upload owner");
        var reservation = new Reservation();
        active.add(reservation);
        reservation.bind(path);
        return reservation;
    }

    private Set<Path> dormantImages() throws IOException {
        directories.removeIf(
                path ->
                        Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
                                && active.stream()
                                        .noneMatch(
                                                owner ->
                                                        owner.image != null
                                                                && path.equals(
                                                                        owner.image.getParent())));
        Set<Path> files = new LinkedHashSet<>();
        for (var directory : directories)
            files.addAll(RecoverySourceFiles.imagesInDirectory(directory));
        for (var project : projects.entrySet()) {
            if (Files.notExists(project.getKey(), LinkOption.NOFOLLOW_LINKS)) continue;
            for (var source : project.getValue())
                files.addAll(RecoverySourceFiles.imagesInDirectory(project.getKey(), source));
        }
        files.removeIf(path -> active.stream().anyMatch(owner -> owner.contains(path)));
        return files;
    }

    private static void requireRunning(BooleanSupplier shouldStop) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (shouldStop.getAsBoolean())
            throw new CancellationException("Image preparation cancelled");
    }

    /** One active prepare/upload operation; any surviving image remains counted after close. */
    final class Reservation implements AutoCloseable {
        private Path image;

        void bind(Path path) {
            synchronized (PreparedImageBudget.this) {
                if (!active.contains(this) || image != null)
                    throw new IllegalStateException(
                            "Prepared-image reservation is already bound or closed");
                image = path.toAbsolutePath().normalize();
                directories.add(image.getParent());
            }
        }

        private boolean contains(Path path) {
            return image != null
                    && (image.equals(path)
                            || image.getParent().equals(path.getParent())
                                    && path.getFileName()
                                            .toString()
                                            .startsWith("." + image.getFileName() + "."));
        }

        @Override
        public void close() {
            synchronized (PreparedImageBudget.this) {
                active.remove(this);
                PreparedImageBudget.this.notifyAll();
            }
        }
    }
}
