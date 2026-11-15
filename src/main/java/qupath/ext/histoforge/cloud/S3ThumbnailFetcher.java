package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/** Reads the smallest TIFF image and limits its display height to 120 pixels. */
class S3ThumbnailFetcher {
    private static final int TARGET_HEIGHT = 120;

    BufferedImage fetch(S3Client client, String bucket, String key) {
        try {
            var snapshot = S3ObjectSnapshot.capture(client, bucket, key);
            var level = S3TiffPyramid.read(client, bucket, key, snapshot).thumbnailLevel();
            BufferedImage image;
            if (level.offsets().length == 1) {
                image =
                        level.byteCounts()[0] == 0
                                ? null
                                : S3TiffPyramid.decodeTile(client, bucket, key, snapshot, level, 0);
            } else {
                image =
                        new BufferedImage(
                                Math.toIntExact(level.width()),
                                Math.toIntExact(level.height()),
                                BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try {
                    for (int y = 0; y < image.getHeight(); y += level.tileHeight()) {
                        for (int x = 0; x < image.getWidth(); x += level.tileWidth()) {
                            int index = level.tileIndex(x, y);
                            if (index >= level.offsets().length || level.byteCounts()[index] == 0)
                                continue;
                            graphics.drawImage(
                                    S3TiffPyramid.decodeTile(
                                            client, bucket, key, snapshot, level, index),
                                    x,
                                    y,
                                    null);
                        }
                    }
                } finally {
                    graphics.dispose();
                }
            }
            if (image == null || image.getHeight() <= TARGET_HEIGHT) return image;
            int width = (int) (image.getWidth() * ((double) TARGET_HEIGHT / image.getHeight()));
            var scaled = new BufferedImage(width, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, width, TARGET_HEIGHT, null);
            } finally {
                graphics.dispose();
            }
            return scaled;
        } catch (IOException failure) {
            LoggerFactory.getLogger(S3ThumbnailFetcher.class)
                    .debug(
                            "Thumbnail unavailable for s3://{}/{}: {}",
                            bucket,
                            key,
                            failure.getMessage());
            return null;
        }
    }
}
