package qupath.ext.histoforge.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.pipeline.ImageActionAggregation.TargetResult;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;

/** Terminal image truth or single-use same-process ownership of unresolved durable targets. */
public sealed interface ImageActionAttempt
        permits ImageActionAttempt.Completed, ImageActionAttempt.Suspended {

    /** Every selected target is terminal and the image reservation has been released. */
    record Completed(RunResults.ImageRunResult result) implements ImageActionAttempt {
        public Completed {
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** Aggregate retained while one target handle requires explicit user action. */
    final class Suspended implements ImageActionAttempt {

        private final ImageActionCoordinator owner;
        private final ImageActionExecutor.Request request;
        private final ImageExecutionPlan fullPlan;
        private final List<TargetResult> terminalResults;
        private final List<ResumeOwnership> pending;
        private final List<ImageActionSubsetPlanner.Subset> deferredTargets;
        private final Optional<FiniteSourceProvisioner.Session> sourceSession;
        private final Optional<SealedOriginalSource> sharedOriginal;
        private final ImageActionRegistry.Reservation reservation;
        private OwnershipState ownershipState = OwnershipState.AVAILABLE;

        Suspended(
                ImageActionCoordinator owner,
                ImageActionExecutor.Request request,
                ImageExecutionPlan fullPlan,
                List<TargetResult> terminalResults,
                List<ResumeOwnership> pending,
                List<ImageActionSubsetPlanner.Subset> deferredTargets,
                Optional<FiniteSourceProvisioner.Session> sourceSession,
                Optional<SealedOriginalSource> sharedOriginal,
                ImageActionRegistry.Reservation reservation) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.request = Objects.requireNonNull(request, "request");
            this.fullPlan = Objects.requireNonNull(fullPlan, "fullPlan");
            this.terminalResults = List.copyOf(terminalResults);
            this.pending = List.copyOf(pending);
            this.deferredTargets = List.copyOf(deferredTargets);
            this.sourceSession = Objects.requireNonNull(sourceSession, "sourceSession");
            this.sharedOriginal = Objects.requireNonNull(sharedOriginal, "sharedOriginal");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
            if (this.pending.size() != 1) {
                throw new IllegalArgumentException(
                        "suspended image requires exactly one unresolved target");
            }
            if (this.sourceSession.isPresent() != this.sharedOriginal.isPresent()) {
                throw new IllegalArgumentException(
                        "shared source session and sealed original must be retained together");
            }
        }

        /**
         * Current durable handles displayed by recovery UI; mutable handle state is not exposed.
         */
        public List<LargeActionHandle> handles() {
            return pending.stream().map(ResumeOwnership::lastObservedHandle).toList();
        }

        /** Current handle plus captured targets that have not started yet. */
        public int unresolvedTargetCount() {
            return pending.size() + deferredTargets.size();
        }

        /** The last failed attempt, retained through the recovery ownership transfer. */
        public Optional<Exception> failure() {
            return pending.getFirst().failure();
        }

        synchronized State claim(ImageActionCoordinator expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "suspended image belongs to another coordinator");
            }
            if (ownershipState != OwnershipState.AVAILABLE) {
                throw new IllegalStateException("suspended image ownership is already resolved");
            }
            ownershipState = OwnershipState.CLAIMED;
            return new State(
                    request,
                    fullPlan,
                    terminalResults,
                    pending,
                    deferredTargets,
                    sourceSession,
                    sharedOriginal,
                    reservation);
        }

        synchronized void resolveClaim(ImageActionCoordinator expectedOwner) {
            requireClaimed(expectedOwner);
            ownershipState = OwnershipState.RESOLVED;
        }

        synchronized void restoreClaim(ImageActionCoordinator expectedOwner) {
            requireClaimed(expectedOwner);
            ownershipState = OwnershipState.AVAILABLE;
        }

        private void requireClaimed(ImageActionCoordinator expectedOwner) {
            if (owner != expectedOwner || ownershipState != OwnershipState.CLAIMED) {
                throw new IllegalStateException("suspended image ownership is not claimed here");
            }
        }

        /** Claimed aggregate state transferred exactly once to resume or abandonment. */
        record State(
                ImageActionExecutor.Request request,
                ImageExecutionPlan fullPlan,
                List<TargetResult> terminalResults,
                List<ResumeOwnership> pending,
                List<ImageActionSubsetPlanner.Subset> deferredTargets,
                Optional<FiniteSourceProvisioner.Session> sourceSession,
                Optional<SealedOriginalSource> sharedOriginal,
                ImageActionRegistry.Reservation reservation) {
            State {
                request = Objects.requireNonNull(request, "request");
                fullPlan = Objects.requireNonNull(fullPlan, "fullPlan");
                terminalResults = List.copyOf(terminalResults);
                pending = List.copyOf(pending);
                deferredTargets = List.copyOf(deferredTargets);
                sourceSession = Objects.requireNonNull(sourceSession, "sourceSession");
                sharedOriginal = Objects.requireNonNull(sharedOriginal, "sharedOriginal");
                reservation = Objects.requireNonNull(reservation, "reservation");
                if (pending.size() != 1) {
                    throw new IllegalArgumentException(
                            "suspended image requires exactly one unresolved target");
                }
                if (sourceSession.isPresent() != sharedOriginal.isPresent()) {
                    throw new IllegalArgumentException(
                            "shared source session and sealed original must be retained together");
                }
            }
        }

        private enum OwnershipState {
            AVAILABLE,
            CLAIMED,
            RESOLVED
        }
    }
}
