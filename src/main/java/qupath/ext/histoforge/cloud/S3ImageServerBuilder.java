package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javafx.application.Platform;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

/** SPI entry point also serialized in QuPath projects; its class name and URI scheme are stable. */
public class S3ImageServerBuilder implements ImageServerBuilder<BufferedImage> {
    public static final String SCHEME = "histoforge-cloud";

    private static boolean handles(URI uri) {
        return SCHEME.equals(uri.getScheme());
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args)
            throws IOException {
        return handles(uri)
                ? UriImageSupport.createInstance(
                        S3ImageServerBuilder.class,
                        4f,
                        List.of(
                                DefaultImageServerBuilder.createInstance(
                                        S3ImageServerBuilder.class, null, uri, args)))
                : null;
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
        if (!handles(uri)) return null;
        var credentials = AwsCredentialManager.getInstance();
        if (credentials != null && credentials.isAuthenticated())
            return new S3SlideImageServer(
                    uri, credentials.getS3Client(), uri.getHost(), uri.getPath().substring(1));
        // QuPath also reports a generic open failure; give the sign-in action here.
        Platform.runLater(
                () ->
                        Dialogs.showWarningNotification(
                                "Cloud Slide",
                                "This slide is stored in the cloud. Sign in via Extensions → HistoForge → Open from Cloud to view it."));
        throw new IOException("Cloud slide — not signed in");
    }

    @Override
    public String getName() {
        return "HistoForge Cloud (S3)";
    }

    @Override
    public String getDescription() {
        return "Remote slides on AWS S3";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }
}
