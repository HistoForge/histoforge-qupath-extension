package qupath.ext.histoforge.client;

import java.util.Objects;

/** Transient progress for one source file; acknowledged bytes are durable gateway receipts. */
public record SourceTransferProgress(Phase phase, long acknowledgedBytes, long totalBytes) {

    public enum Phase {
        PREPARING,
        UPLOADING,
        VERIFYING,
        READY
    }

    public SourceTransferProgress {
        Objects.requireNonNull(phase, "phase");
        if (acknowledgedBytes < 0 || totalBytes < 0 || acknowledgedBytes > totalBytes) {
            throw new IllegalArgumentException(
                    "source progress requires 0 <= acknowledged <= total");
        }
        if (phase == Phase.PREPARING) {
            if (acknowledgedBytes != 0) {
                throw new IllegalArgumentException("source preparation has no acknowledged bytes");
            }
        } else if (totalBytes == 0) {
            throw new IllegalArgumentException("source transfer requires a positive file size");
        } else if ((phase == Phase.VERIFYING || phase == Phase.READY)
                && acknowledgedBytes != totalBytes) {
            throw new IllegalArgumentException(
                    "source verification requires every byte acknowledged");
        }
    }

    /** Source encoding or local identity preparation, before its size is necessarily known. */
    public static SourceTransferProgress preparing() {
        return new SourceTransferProgress(Phase.PREPARING, 0, 0);
    }
}
