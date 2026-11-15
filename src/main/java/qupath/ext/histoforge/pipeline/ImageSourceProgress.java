package qupath.ext.histoforge.pipeline;

import java.util.Optional;
import qupath.ext.histoforge.client.SourceTransferProgress;

/**
 * Display-only source progress, bound to the image action that owns the upload. An empty transfer
 * clears a failed preparation/upload before other ROIs continue; it does not mark that file ready.
 */
public record ImageSourceProgress(
        String actionId, String imageId, Optional<SourceTransferProgress> transfer) {
    public ImageSourceProgress(String actionId, String imageId, SourceTransferProgress transfer) {
        this(actionId, imageId, Optional.of(transfer));
    }
}
