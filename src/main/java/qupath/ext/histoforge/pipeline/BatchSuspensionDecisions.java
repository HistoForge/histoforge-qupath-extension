package qupath.ext.histoforge.pipeline;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Exactly-one explicit Resume/Abandon decision for every currently suspended image. */
final class BatchSuspensionDecisions {

    enum Decision {
        RESUME,
        ABANDON
    }

    private final BooleanSupplier batchCancelled;
    private final Map<String, Control> controls = new ConcurrentHashMap<>();

    BatchSuspensionDecisions(BooleanSupplier batchCancelled) {
        this.batchCancelled = Objects.requireNonNull(batchCancelled, "batchCancelled");
    }

    Decision await(String imageId, boolean automaticallyAbandon, Runnable suspensionPublished) {
        String id = BatchOrchestrator.requireText(imageId, "imageId");
        Control control = new Control();
        if (controls.putIfAbsent(id, control) != null) {
            throw new IllegalStateException("image already has a suspended batch decision");
        }
        try {
            Objects.requireNonNull(suspensionPublished, "suspensionPublished").run();
            if (batchCancelled.getAsBoolean() && automaticallyAbandon) {
                control.resolve(Decision.ABANDON);
            }
            return control.decision().join();
        } finally {
            controls.remove(id, control);
        }
    }

    boolean resume(String imageId) {
        return resolve(imageId, Decision.RESUME);
    }

    boolean abandon(String imageId) {
        return resolve(imageId, Decision.ABANDON);
    }

    void cancelAll() {
        controls.values().forEach(control -> control.resolve(Decision.ABANDON));
    }

    private boolean resolve(String imageId, Decision decision) {
        Control control = controls.get(BatchOrchestrator.requireText(imageId, "imageId"));
        return control != null && control.resolve(decision);
    }

    private record Control(CompletableFuture<Decision> decision) {
        private Control() {
            this(new CompletableFuture<>());
        }

        private Control {
            decision = Objects.requireNonNull(decision, "decision");
        }

        private boolean resolve(Decision value) {
            return decision.complete(Objects.requireNonNull(value, "value"));
        }
    }
}
