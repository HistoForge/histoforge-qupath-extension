package qupath.ext.histoforge.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import qupath.lib.gui.prefs.PathPrefs;

/** Existing user-local directory shared by cloud settings and replaceable thumbnails. */
final class CloudStoragePaths {
    private CloudStoragePaths() {}

    static Path directory() {
        String configured = PathPrefs.userPathProperty().get();
        if (configured != null && !configured.isBlank() && Files.isDirectory(Path.of(configured)))
            return Path.of(configured, "extensions", "histoforge", "cloud");
        return Path.of(System.getProperty("user.home"), ".histoforge", "cloud");
    }
}
