package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;

/** Terminal result or retained process-local ownership of one recoverable action attempt. */
sealed interface RecoverableActionAttempt
        permits RecoverableActionAttempt.Completed, RecoverableActionAttempt.Suspended {

    /** Terminal persisted-state truth; the caller owns final reservation release. */
    record Completed(RunResults.ImageRunResult result, boolean automaticContinuationAllowed)
            implements RecoverableActionAttempt {
        public Completed {
            result = Objects.requireNonNull(result, "result");
        }

        Completed(RunResults.ImageRunResult result) {
            this(result, true);
        }
    }

    /**
     * Process-local ownership of one failed attempt whose persisted handle remains resumable.
     *
     * <p>The reservation is intentionally retained until an explicit recovery owner resumes the
     * action or {@link #abandon()} durably cancels it. Merely observing the failure never permits a
     * second HistoForge mutation to race the unresolved handle.
     */
    final class Suspended implements RecoverableActionAttempt {

        private final LargeActionHandle lastObservedHandle;
        private final Optional<ImageExecutionPlan> lastObservedPlan;
        private final List<RunResults.ScopeWorkResult> observedWork;
        private final Exception failure;
        private final LargeActionHandleStore handles;
        private final ImageActionRegistry.Reservation reservation;
        private boolean resolved;

        Suspended(
                LargeActionHandle lastObservedHandle,
                ImageExecutionPlan lastObservedPlan,
                List<RunResults.ScopeWorkResult> observedWork,
                Exception failure,
                LargeActionHandleStore handles,
                ImageActionRegistry.Reservation reservation) {
            this.lastObservedHandle =
                    Objects.requireNonNull(lastObservedHandle, "lastObservedHandle");
            this.lastObservedPlan = Optional.ofNullable(lastObservedPlan);
            this.observedWork = List.copyOf(Objects.requireNonNull(observedWork, "observedWork"));
            if (this.lastObservedPlan.isEmpty() && !this.observedWork.isEmpty()) {
                throw new IllegalArgumentException(
                        "observed scope work requires its process-local execution plan");
            }
            this.failure = Objects.requireNonNull(failure, "failure");
            this.handles = Objects.requireNonNull(handles, "handles");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        LargeActionHandle lastObservedHandle() {
            return lastObservedHandle;
        }

        Exception failure() {
            return failure;
        }

        /** Transfer the still-held mutation reservation into one explicit resume attempt. */
        synchronized ResumeOwnership claimForResume() {
            requireUnresolved();
            ResumeOwnership ownership =
                    new ResumeOwnership(
                            lastObservedHandle,
                            lastObservedPlan,
                            observedWork,
                            reservation,
                            Optional.of(failure));
            resolved = true;
            return ownership;
        }

        synchronized LargeActionHandle abandon() throws IOException {
            requireUnresolved();
            boolean interrupted = Thread.interrupted();
            try {
                LargeActionHandle cancelled =
                        handles.abandon(
                                lastObservedHandle.actionId(),
                                lastObservedHandle.handleGenerationId());
                reservation.close();
                resolved = true;
                return cancelled;
            } catch (UnrecoverableRecoveryStateException failure) {
                reservation.close();
                resolved = true;
                throw failure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void requireUnresolved() {
            if (resolved) {
                throw new IllegalStateException("suspended action ownership is already resolved");
            }
        }
    }

    /**
     * Exactly-once transfer of one unresolved handle and its process-local mutation reservation.
     */
    record ResumeOwnership(
            LargeActionHandle lastObservedHandle,
            Optional<ImageExecutionPlan> lastObservedPlan,
            List<RunResults.ScopeWorkResult> observedWork,
            ImageActionRegistry.Reservation reservation,
            Optional<Exception> failure) {
        public ResumeOwnership {
            lastObservedHandle = Objects.requireNonNull(lastObservedHandle, "lastObservedHandle");
            lastObservedPlan = Objects.requireNonNull(lastObservedPlan, "lastObservedPlan");
            observedWork = List.copyOf(Objects.requireNonNull(observedWork, "observedWork"));
            reservation = Objects.requireNonNull(reservation, "reservation");
            failure = Objects.requireNonNull(failure, "failure");
            if (lastObservedPlan.isEmpty() && !observedWork.isEmpty()) {
                throw new IllegalArgumentException(
                        "observed scope work requires its process-local execution plan");
            }
            if (!reservation.imageId().equals(lastObservedHandle.imageId())) {
                throw new IllegalArgumentException(
                        "resume reservation belongs to another project image");
            }
        }

        ResumeOwnership withFailure(Exception cause) {
            return new ResumeOwnership(
                    lastObservedHandle,
                    lastObservedPlan,
                    observedWork,
                    reservation,
                    Optional.of(cause));
        }
    }
}
