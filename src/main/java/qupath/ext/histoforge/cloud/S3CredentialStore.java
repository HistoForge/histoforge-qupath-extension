package qupath.ext.histoforge.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.util.SecureFileStore;

/**
 * Stores connection fields and SSO tokens as owner-readable JSON in the cloud settings directory.
 */
public class S3CredentialStore {
    private static final Gson JSON = new Gson();
    private final Path directory;

    public S3CredentialStore() {
        this(null);
    }

    S3CredentialStore(Path directory) {
        this.directory = directory;
    }

    private Path file(String name) {
        return (directory == null ? CloudStoragePaths.directory() : directory).resolve(name);
    }

    public void saveConfig(S3ConnectionConfig config) throws IOException {
        SecureFileStore.writeOwnerOnly(file("config.json"), JSON.toJson(config));
    }

    public Optional<S3ConnectionConfig> loadConfig() {
        return read("config.json", S3ConnectionConfig.class);
    }

    public void saveSsoToken(SsoTokenData token) throws IOException {
        SecureFileStore.writeOwnerOnly(file("sso_token.json"), JSON.toJson(token));
    }

    public Optional<SsoTokenData> loadSsoToken() {
        return read("sso_token.json", SsoTokenData.class);
    }

    /** Clearing login never removes the connection fields used to sign in again. */
    public void clearSsoToken() throws IOException {
        Files.deleteIfExists(file("sso_token.json"));
    }

    private <T> Optional<T> read(String name, Class<T> type) {
        Path path = file(name);
        if (!Files.exists(path)) return Optional.empty();
        try {
            SecureFileStore.enforceExistingOwnerOnly(path);
            return Optional.ofNullable(JSON.fromJson(Files.readString(path), type));
        } catch (IOException | JsonSyntaxException failure) {
            LoggerFactory.getLogger(S3CredentialStore.class)
                    .warn("Could not load {}: {}", name, failure.getMessage());
            return Optional.empty();
        }
    }
}
