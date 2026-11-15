package qupath.ext.histoforge.data;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Manages treatment group assignments stored in {@link ProjectImageEntry} metadata.
 *
 * <p>Groups are stored under {@code HistoforgeKeys.GROUP} as simple strings. Assignment updates the
 * live project first and separately reports whether QuPath confirmed project persistence.
 */
public class GroupManager {
    public static final String UNGROUPED = "Ungrouped";

    private GroupManager() {}

    /** Reads the assignment without substituting a display label for missing metadata. */
    public static String getGroup(ProjectImageEntry<?> entry) {
        return entry.getMetadata().get(HistoforgeKeys.GROUP);
    }

    /** Assign one normalized group and report whether QuPath confirmed project persistence. */
    public static Assignment assignGroup(
            Project<?> project, List<? extends ProjectImageEntry<?>> entries, String group) {
        String normalized = group == null || group.isBlank() ? null : group.strip();
        return new Assignment(
                normalized,
                ProjectMetadataEditor.setAndSync(
                        project, entries, HistoforgeKeys.GROUP, normalized));
    }

    public record Assignment(String group, Optional<Throwable> persistenceFailure) {
        public Assignment {
            persistenceFailure = Objects.requireNonNull(persistenceFailure, "persistenceFailure");
        }
    }

    /** Sorted nonblank assignments for the group chooser. */
    public static List<String> getAllGroups(Project<BufferedImage> project) {
        var groups = new java.util.TreeSet<String>();
        for (var entry : project.getImageList()) {
            String group = getGroup(entry);
            if (group != null && !group.isBlank()) groups.add(group);
        }
        return new java.util.ArrayList<>(groups);
    }
}
