package qupath.ext.histoforge.cloud;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.ssooidc.model.SsoOidcException;

/** Detached S3 listing, filtering and pagination used by the cloud browser. */
final class CloudSlideListing {
    private CloudSlideListing() {}

    static final int PAGE_SIZE = 10;
    static final String ALL = "All";
    static final String SELECTED = "Selected";
    static final String NOT_IN_PROJECT = "Not in Project";
    static final String IN_PROJECT = "In Project";

    record Slide(String key, long size, Instant modified, String etag) {
        String filename() {
            return key.substring(key.lastIndexOf('/') + 1);
        }

        URI uri(String bucket) throws URISyntaxException {
            return new URI(S3ImageServerBuilder.SCHEME, bucket, "/" + key, null);
        }
    }

    record Page(List<Slide> slides, int index, int pages, String label) {}

    static List<Slide> load(S3Client client, S3ConnectionConfig config) {
        var request =
                ListObjectsV2Request.builder()
                        .bucket(config.bucket())
                        .prefix(config.keyPrefix())
                        .build();
        var slides = new ArrayList<Slide>();
        for (var page : client.listObjectsV2Paginator(request)) {
            for (var object : page.contents()) {
                if (isSlide(object.key()))
                    slides.add(
                            new Slide(
                                    object.key(),
                                    object.size(),
                                    object.lastModified(),
                                    object.eTag()));
            }
        }
        slides.sort(Comparator.comparing(Slide::modified).reversed());
        return List.copyOf(slides);
    }

    static boolean isSlide(String key) {
        int dot = key.lastIndexOf('.');
        if (dot <= key.lastIndexOf('/')) return false;
        String extension = key.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Set.of("tif", "tiff", "svs").contains(extension);
    }

    static List<Slide> filter(
            List<Slide> slides,
            String query,
            String mode,
            Set<String> selected,
            Set<String> inProject) {
        String text = query.strip().toLowerCase(Locale.ROOT);
        return slides.stream()
                .filter(slide -> slide.filename().toLowerCase(Locale.ROOT).contains(text))
                .filter(
                        slide ->
                                switch (mode) {
                                    case SELECTED -> selected.contains(slide.key());
                                    case IN_PROJECT -> inProject.contains(slide.key());
                                    case NOT_IN_PROJECT -> !inProject.contains(slide.key());
                                    default -> true;
                                })
                .toList();
    }

    static Page page(List<Slide> slides, int requested) {
        int pages = Math.max(1, (slides.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int index = Math.clamp(requested, 0, pages - 1);
        int start = index * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, slides.size());
        String label =
                slides.isEmpty()
                        ? "0 slides"
                        : "Displaying " + (start + 1) + "–" + end + " of " + slides.size();
        return new Page(slides.subList(start, end), index, pages, label);
    }

    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String unit = "B";
        for (String next : List.of("KB", "MB", "GB")) {
            value /= 1024;
            unit = next;
            if (value < 1024) break;
        }
        return new DecimalFormat("#.#").format(value) + " " + unit;
    }

    static boolean authenticationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof S3Exception s3 && s3.statusCode() == 403
                    || cause instanceof SsoOidcException) return true;
        }
        return false;
    }
}
