package qupath.ext.histoforge.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Applies one project-metadata edit and reports whether QuPath confirmed its persistence. */
public final class ProjectMetadataEditor {

    private ProjectMetadataEditor() {}

    public static Optional<Throwable> setAndSync(
            Project<?> project,
            List<? extends ProjectImageEntry<?>> entries,
            String key,
            String value) {
        Project<?> target = Objects.requireNonNull(project, "project");
        String metadataKey = Objects.requireNonNull(key, "key");
        List<? extends ProjectImageEntry<?>> requested = List.copyOf(entries);
        List<OriginalValue> originals = new ArrayList<>();
        try {
            for (ProjectImageEntry<?> entry : requested) {
                Map<String, String> metadata = entry.getMetadata();
                originals.add(
                        new OriginalValue(
                                entry,
                                metadata.containsKey(metadataKey),
                                metadata.get(metadataKey)));
                if (value == null) {
                    metadata.remove(metadataKey);
                } else {
                    metadata.put(metadataKey, value);
                }
            }
        } catch (RuntimeException failure) {
            restore(originals, metadataKey, failure);
            throw failure;
        }
        try {
            target.syncChanges();
            return Optional.empty();
        } catch (IOException | RuntimeException failure) {
            // QuPath's project sync is not atomic: a late failure may occur after the project file
            // was replaced. Retain the intended in-memory state so an explicit retry is safe and
            // the UI never contradicts a change that may already be durable.
            return Optional.of(failure);
        }
    }

    private static void restore(
            List<OriginalValue> originals, String key, Throwable mutationFailure) {
        for (OriginalValue original : originals.reversed()) {
            try {
                Map<String, String> metadata = original.entry().getMetadata();
                if (original.present()) {
                    metadata.put(key, original.value());
                } else {
                    metadata.remove(key);
                }
            } catch (RuntimeException rollbackFailure) {
                mutationFailure.addSuppressed(rollbackFailure);
            }
        }
    }

    private record OriginalValue(ProjectImageEntry<?> entry, boolean present, String value) {}
}
