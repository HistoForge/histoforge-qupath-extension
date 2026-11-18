package qupath.ext.histoforge.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;

/** Terminal model transaction result. Metrics are a separate post-save phase. */
public record LargeActionCommitResult(
        LargeActionHandle handle,
        Optional<ImageLifecycle.CommitResult> imageCommit,
        Optional<RasterInputCodec.ImageRevisionToken> finalRevision,
        List<RunResults.MissingGenerationWarning> warnings) {

    public LargeActionCommitResult {
        handle = Objects.requireNonNull(handle, "handle");
        imageCommit = Objects.requireNonNull(imageCommit, "imageCommit");
        finalRevision = Objects.requireNonNull(finalRevision, "finalRevision");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (!handle.phase().terminal()) {
            throw new IllegalArgumentException("large-action result must be terminal");
        }
        if (imageCommit.isPresent() && handle.phase() != LargeActionPhase.COMMITTED) {
            throw new IllegalArgumentException(
                    "only a committed action can expose a QuPath save result");
        }
        if ((handle.phase() == LargeActionPhase.COMMITTED) != finalRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "final revision is present exactly for a committed large action");
        }
        if (imageCommit.isPresent()
                && !imageCommit.orElseThrow().savedRevision().equals(finalRevision.orElseThrow())) {
            throw new IllegalArgumentException(
                    "save result and authoritative final revision disagree");
        }
        if (!warnings.isEmpty() && handle.phase() != LargeActionPhase.COMMITTED) {
            throw new IllegalArgumentException(
                    "audit warnings are exposed only after the transaction committed");
        }
    }
}
