package qupath.ext.histoforge.util;

import java.awt.Desktop;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a URL in the user's default browser on a virtual thread (the OS handoff can briefly block),
 * best-effort — a failure or an unsupported {@link Desktop} is logged, never thrown. For
 * fire-and-forget links like "View on Hugging Face"; flow code that must know the outcome should
 * call {@link Desktop} directly.
 */
public final class BrowserLauncher {

    private static final Logger logger = LoggerFactory.getLogger(BrowserLauncher.class);

    private BrowserLauncher() {}

    /** Open {@code url} in the default browser; logs (never throws) on failure. */
    public static void open(String url) {
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                if (Desktop.isDesktopSupported()
                                        && Desktop.getDesktop()
                                                .isSupported(Desktop.Action.BROWSE)) {
                                    Desktop.getDesktop().browse(URI.create(url));
                                } else {
                                    logger.warn("Desktop browse unsupported; cannot open {}", url);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to open {}", url, e);
                            }
                        });
    }
}
