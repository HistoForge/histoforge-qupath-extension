package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.projects.Project;

/** Adds cloud image references to the live project and reports persistence separately. */
final class CloudProjectImporter {

    private CloudProjectImporter() {}

    static Result add(Project<BufferedImage> project, String bucket, List<Slide> slides) {
        Project<BufferedImage> target = Objects.requireNonNull(project, "project");
        String targetBucket = Objects.requireNonNull(bucket, "bucket");
        List<Slide> requested = List.copyOf(slides);
        List<Slide> added = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (Slide slide : requested) {
            try {
                URI uri =
                        new URI(S3ImageServerBuilder.SCHEME, targetBucket, "/" + slide.key(), null);
                var builder =
                        ImageServerBuilder.DefaultImageServerBuilder.createInstance(
                                S3ImageServerBuilder.class, uri);
                var entry = target.addImage(builder);
                if (entry == null) {
                    throw new IllegalStateException("QuPath did not add a project entry");
                }
                added.add(slide);
                try {
                    entry.setImageName(slide.filename());
                } catch (RuntimeException failure) {
                    failures.add(new Failure(slide, failure));
                }
            } catch (Exception failure) {
                failures.add(new Failure(slide, failure));
            }
        }

        Throwable persistenceFailure = null;
        if (!added.isEmpty()) {
            try {
                target.syncChanges();
            } catch (Exception failure) {
                persistenceFailure = failure;
            }
        }
        return new Result(added, failures, Optional.ofNullable(persistenceFailure));
    }

    record Slide(String key, String filename) {
        Slide {
            key = Objects.requireNonNull(key, "key");
            filename = Objects.requireNonNull(filename, "filename");
        }
    }

    record Failure(Slide slide, Exception cause) {
        Failure {
            slide = Objects.requireNonNull(slide, "slide");
            cause = Objects.requireNonNull(cause, "cause");
        }
    }

    record Result(
            List<Slide> added, List<Failure> failures, Optional<Throwable> persistenceFailure) {
        Result {
            added = List.copyOf(added);
            failures = List.copyOf(failures);
            persistenceFailure = Objects.requireNonNull(persistenceFailure, "persistenceFailure");
        }
    }
}
