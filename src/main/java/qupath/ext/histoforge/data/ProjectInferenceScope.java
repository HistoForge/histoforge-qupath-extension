package qupath.ext.histoforge.data;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.roi.AnnotationRoiSelection;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

/** Project-owned choice of inference boundaries; captured target geometry lives in action plans. */
public record ProjectInferenceScope(Mode mode, Optional<String> annotationClass) {
    public enum Mode {
        HISTOFORGE_ROIS("HistoForge ROIs"),
        ANNOTATIONS_BY_CLASS("Annotations by class"),
        WHOLE_SLIDE("Whole slide");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public boolean wholeSlide() {
            return this == WHOLE_SLIDE;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public ProjectInferenceScope {
        mode = Objects.requireNonNull(mode, "mode");
        annotationClass =
                Objects.requireNonNull(annotationClass, "annotationClass")
                        .map(
                                value ->
                                        CanonicalCodec.requirePathClassName(
                                                value, "Annotation class"));
    }

    public static ProjectInferenceScope defaults() {
        return new ProjectInferenceScope(Mode.HISTOFORGE_ROIS, Optional.empty());
    }

    public Optional<String> selectedClass() {
        return mode == Mode.ANNOTATIONS_BY_CLASS ? annotationClass : Optional.empty();
    }

    public boolean selects(PathObject object) {
        return mode == Mode.ANNOTATIONS_BY_CLASS
                ? selectedClass()
                        .filter(value -> AnnotationRoiSelection.matches(object, value))
                        .isPresent()
                : RoiRegistry.isAdministrativeRoi(object);
    }

    public static ProjectInferenceScope read(Project<?> project) {
        var metadata = Objects.requireNonNull(project, "project").getMetadata();
        var mode =
                Optional.ofNullable(metadata.get(HistoforgeKeys.INFERENCE_SCOPE))
                        .map(Mode::valueOf)
                        .orElse(Mode.HISTOFORGE_ROIS);
        return new ProjectInferenceScope(
                mode, Optional.ofNullable(metadata.get(HistoforgeKeys.INFERENCE_ANNOTATION_CLASS)));
    }

    /** QuPath owns persistence; a failed save leaves the chosen values available for a retry. */
    public void save(Project<?> project) throws IOException {
        var metadata = Objects.requireNonNull(project, "project").getMetadata();
        metadata.put(HistoforgeKeys.INFERENCE_SCOPE, mode.name());
        if (annotationClass.isPresent())
            metadata.put(HistoforgeKeys.INFERENCE_ANNOTATION_CLASS, annotationClass.orElseThrow());
        else metadata.remove(HistoforgeKeys.INFERENCE_ANNOTATION_CLASS);
        project.syncChanges();
    }
}
