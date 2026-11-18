package qupath.ext.histoforge.data;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/** Project thumbnails with a disposable ROI overlay; the cached base image is never painted on. */
public class ThumbnailRenderer {
    private ThumbnailRenderer() {}

    public record RoiOverlay(double x, double y, double width, double height) {}

    public static Image generateWsiThumbnail(
            ProjectImageEntry<BufferedImage> entry, List<PathObject> annotations) {
        var overlays =
                annotations.stream()
                        .filter(RoiManager::isHistoforgeRoi)
                        .map(PathObject::getROI)
                        .map(
                                roi ->
                                        new RoiOverlay(
                                                roi.getBoundsX(),
                                                roi.getBoundsY(),
                                                roi.getBoundsWidth(),
                                                roi.getBoundsHeight()))
                        .toList();
        return generateWsiThumbnail(entry, overlays);
    }

    /** Callers may pass detached rectangles from a worker without retaining hierarchy objects. */
    public static Image generateWsiThumbnail(
            ProjectImageEntry<BufferedImage> entry, Iterable<RoiOverlay> overlays) {
        try {
            BufferedImage base = entry.getThumbnail();
            var builder = entry.getServerBuilder();
            if (base == null && builder != null) {
                try (var server = builder.build()) {
                    if (server != null) {
                        double downsample =
                                Math.max(
                                        1,
                                        Math.max(server.getWidth(), server.getHeight()) / 2048.0);
                        base =
                                server.readRegion(
                                        RegionRequest.createInstance(
                                                server.getPath(),
                                                downsample,
                                                0,
                                                0,
                                                server.getWidth(),
                                                server.getHeight()));
                        if (base != null) entry.setThumbnail(base);
                    }
                }
            }
            if (base == null) return null;
            var metadata = builder == null ? null : builder.getMetadata().orElse(null);
            if (metadata != null && metadata.getWidth() > 0 && metadata.getHeight() > 0)
                base = overlay(base, overlays, metadata.getWidth(), metadata.getHeight());
            return SwingFXUtils.toFXImage(base, null);
        } catch (Exception failure) {
            LoggerFactory.getLogger(ThumbnailRenderer.class)
                    .warn(
                            "Thumbnail unavailable for {}: {}",
                            entry.getImageName(),
                            failure.getMessage());
            return null;
        }
    }

    static BufferedImage overlay(
            BufferedImage base, Iterable<RoiOverlay> overlays, int sourceWidth, int sourceHeight) {
        var rectangles = overlays.iterator();
        if (!rectangles.hasNext()) return base;
        int type =
                base.getType() == BufferedImage.TYPE_CUSTOM
                        ? BufferedImage.TYPE_INT_ARGB
                        : base.getType();
        var painted = new BufferedImage(base.getWidth(), base.getHeight(), type);
        var graphics = painted.createGraphics();
        try {
            graphics.drawImage(base, 0, 0, null);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(RoiManager.HISTOFORGE_ROI_COLOR));
            graphics.setStroke(
                    new BasicStroke(
                            (float)
                                    Math.max(
                                            2,
                                            Math.max(base.getWidth(), base.getHeight()) / 500.0)));
            double scaleX = (double) base.getWidth() / sourceWidth;
            double scaleY = (double) base.getHeight() / sourceHeight;
            while (rectangles.hasNext()) {
                var rectangle = rectangles.next();
                graphics.drawRect(
                        (int) (rectangle.x() * scaleX),
                        (int) (rectangle.y() * scaleY),
                        (int) (rectangle.width() * scaleX),
                        (int) (rectangle.height() * scaleY));
            }
        } finally {
            graphics.dispose();
        }
        return painted;
    }
}
