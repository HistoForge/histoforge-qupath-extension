package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;

/** Optional PNG cache. Changing an object's ETag selects a different cache entry. */
class ThumbnailCache {
    Optional<Image> load(String bucket, String key, String etag) {
        try {
            var path = path(bucket, key, etag);
            if (!Files.isRegularFile(path)) return Optional.empty();
            BufferedImage pixels = ImageIO.read(path.toFile());
            return pixels == null
                    ? Optional.empty()
                    : Optional.of(SwingFXUtils.toFXImage(pixels, null));
        } catch (Exception failure) {
            LoggerFactory.getLogger(ThumbnailCache.class)
                    .debug("Thumbnail cache unavailable: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    void save(String bucket, String key, String etag, BufferedImage pixels) {
        try {
            Path path = path(bucket, key, etag);
            Files.createDirectories(path.getParent());
            ImageIO.write(pixels, "PNG", path.toFile());
        } catch (IOException failure) {
            LoggerFactory.getLogger(ThumbnailCache.class)
                    .debug("Could not cache thumbnail: {}", failure.getMessage());
        }
    }

    private static Path path(String bucket, String key, String etag) {
        try {
            byte[] input = (bucket + "/" + key + "/" + etag).getBytes(StandardCharsets.UTF_8);
            String name =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            return CloudStoragePaths.directory().resolve("thumbnails").resolve(name + ".png");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required SHA-256 implementation is unavailable", impossible);
        }
    }
}
