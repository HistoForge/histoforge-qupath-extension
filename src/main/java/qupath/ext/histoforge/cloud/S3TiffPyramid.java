package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/** TIFF directory metadata shared by slide rendering and browser thumbnails. */
record S3TiffPyramid(List<Level> levels, Level thumbnailLevel, double micronsPerPixel) {
    record Level(
            long width,
            long height,
            int tileWidth,
            int tileHeight,
            long[] offsets,
            long[] byteCounts) {
        int tileIndex(int x, int y) {
            long columns = (width + tileWidth - 1) / tileWidth;
            return Math.toIntExact((y / tileHeight) * columns + x / tileWidth);
        }
    }

    static S3TiffPyramid read(S3Client client, String bucket, String key, S3ObjectSnapshot snapshot)
            throws IOException {
        try (var input =
                new RandomAccessInputStream(new S3RandomAccess(client, bucket, key, snapshot))) {
            var parser = new TiffParser(input);
            var directories = parser.getMainIFDs();
            if (directories.isEmpty()) throw new IOException("TIFF contains no image directories");
            var levels = new ArrayList<Level>();
            for (IFD directory : directories) {
                parser.fillInIFD(directory);
                levels.add(
                        new Level(
                                directory.getImageWidth(),
                                directory.getImageLength(),
                                Math.toIntExact(directory.getTileWidth()),
                                Math.toIntExact(directory.getTileLength()),
                                directory.getStripOffsets(),
                                directory.getStripByteCounts()));
            }
            Level smallest =
                    levels.stream()
                            .min(Comparator.comparingLong(level -> level.width() * level.height()))
                            .orElseThrow();
            double calibration = calibration(directories.getFirst());
            levels.sort(Comparator.comparingLong(Level::width).reversed());
            return new S3TiffPyramid(List.copyOf(levels), smallest, calibration);
        } catch (FormatException failure) {
            throw new IOException(
                    "Failed to parse TIFF structure from S3: " + failure.getMessage(), failure);
        }
    }

    /** XResolution is pixels per declared unit; absent or unitless values are not calibration. */
    private static double calibration(IFD directory) {
        try {
            var resolution = directory.getIFDRationalValue(IFD.X_RESOLUTION);
            if (resolution == null || resolution.doubleValue() <= 0) return 0;
            double unitMicrons =
                    switch (directory.getIFDIntValue(IFD.RESOLUTION_UNIT, 2)) {
                        case 2 -> 25_400;
                        case 3 -> 10_000;
                        default -> 0;
                    };
            return unitMicrons / resolution.doubleValue();
        } catch (Exception failure) {
            LoggerFactory.getLogger(S3TiffPyramid.class)
                    .warn("Could not read TIFF calibration: {}", failure.getMessage());
            return 0;
        }
    }

    static BufferedImage decodeTile(
            S3Client client,
            String bucket,
            String key,
            S3ObjectSnapshot snapshot,
            Level level,
            int index)
            throws IOException {
        long start = level.offsets()[index];
        long end = start + level.byteCounts()[index] - 1;
        try {
            byte[] encoded =
                    client.getObjectAsBytes(
                                    snapshot.rangeRequest(
                                            bucket, key, "bytes=" + start + "-" + end))
                            .asByteArray();
            if (encoded.length != level.byteCounts()[index])
                throw new IOException("Incomplete S3 tile response");
            return ImageIO.read(new ByteArrayInputStream(encoded));
        } catch (RuntimeException failure) {
            throw new IOException("S3 tile read failed: " + failure.getMessage(), failure);
        }
    }
}
