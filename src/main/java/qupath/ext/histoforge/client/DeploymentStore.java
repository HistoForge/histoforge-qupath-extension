package qupath.ext.histoforge.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.util.SecureFileStore;

/**
 * Reads the gateway URL + bearer token from {@code ~/.histoforge/deployment.json}, the handoff file
 * written by the {@code histoforge} CLI ({@code deploy}, {@code connect}, or {@code upgrade}).
 *
 * <p>{@link #load()} caches the parsed value (so repeated polls don't re-read the file); {@link
 * #reload()} forces a re-read, used after a 401 to pick up a token the user rotated. Missing or
 * unreadable or invalid file → empty (logged, never throws) so callers can show a clean "not
 * connected" message. Mirrors {@code cloud.S3CredentialStore}.
 */
public final class DeploymentStore {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentStore.class);
    private final Path path;
    private volatile Deployment cached;
    private volatile boolean loaded;

    public DeploymentStore() {
        this(Path.of(System.getProperty("user.home"), ".histoforge", "deployment.json"));
    }

    /** Test seam: read from an explicit path. */
    DeploymentStore(Path path) {
        this.path = path;
    }

    /** Cached read; loads from disk on first call. Empty if not deployed / unreadable. */
    public Optional<Deployment> load() {
        return loaded ? Optional.ofNullable(cached) : reload();
    }

    /** Force a re-read from disk (e.g. after a 401, to pick up a rotated token). */
    public synchronized Optional<Deployment> reload() {
        loaded = true;
        try {
            if (!Files.exists(path)) {
                cached = null;
            } else {
                SecureFileStore.enforceExistingOwnerOnly(path);
                cached = Deployment.parse(CanonicalCodec.parseJson(Files.readString(path)));
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Failed to read {}: {}", path, e.getMessage());
            cached = null;
        }
        return Optional.ofNullable(cached);
    }
}
