package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;
import qupath.lib.projects.ProjectImageEntry;

/** Row model for the Project tab's image table. Mutable properties for JavaFX binding. */
public class ImageRowModel {
    final ProjectImageEntry<BufferedImage> entry;
    final StringProperty group;
    final StringProperty moduleName;
    String moduleId;
    final Set<String> roiIds;
    final int roiCount;
    final Optional<String> annotationClass;
    final StringProperty status;
    final StringProperty lastBatchStatus;
    final Image wsiThumbnail;
    final BooleanProperty selected;
    // The assigned module ref when it isn't installed, else null — drives the Module column's
    // "⚠ … not installed" cell + click-to-install. Cleared on in-place re-assign (Set Module).
    String missingModuleRef;

    ImageRowModel(
            ProjectImageEntry<BufferedImage> entry,
            String group,
            String moduleName,
            String moduleId,
            Set<String> roiIds,
            String status,
            String lastBatchStatus,
            Image thumbnail,
            String missingModuleRef) {
        this(
                entry,
                group,
                moduleName,
                moduleId,
                roiIds,
                status,
                lastBatchStatus,
                thumbnail,
                missingModuleRef,
                Optional.empty());
    }

    ImageRowModel(
            ProjectImageEntry<BufferedImage> entry,
            String group,
            String moduleName,
            String moduleId,
            Set<String> roiIds,
            String status,
            String lastBatchStatus,
            Image thumbnail,
            String missingModuleRef,
            Optional<String> annotationClass) {
        this.entry = entry;
        this.group = new SimpleStringProperty(group != null ? group : "—");
        this.moduleName = new SimpleStringProperty(moduleName != null ? moduleName : "—");
        this.moduleId = moduleId;
        this.roiIds = Set.copyOf(roiIds);
        this.annotationClass = java.util.Objects.requireNonNull(annotationClass);
        this.roiCount = this.roiIds.size();
        this.status = new SimpleStringProperty(status);
        this.lastBatchStatus =
                new SimpleStringProperty(lastBatchStatus != null ? lastBatchStatus : "");
        this.wsiThumbnail = thumbnail;
        this.selected = new SimpleBooleanProperty(false);
        this.missingModuleRef = missingModuleRef;
    }

    public StringProperty groupProperty() {
        return group;
    }

    public StringProperty moduleNameProperty() {
        return moduleName;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public StringProperty lastBatchStatusProperty() {
        return lastBatchStatus;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }
}
