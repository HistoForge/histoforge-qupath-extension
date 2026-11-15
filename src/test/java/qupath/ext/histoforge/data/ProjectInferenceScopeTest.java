package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Projects;
import qupath.lib.roi.ROIs;

class ProjectInferenceScopeTest {
    @TempDir Path directory;

    @Test
    void scopeAndClassSurviveNativeProjectReloadWithoutLeakingIntoAnotherProject()
            throws Exception {
        var first =
                Projects.createProject(
                        Files.createDirectory(directory.resolve("first")).toFile(),
                        BufferedImage.class);
        var second =
                Projects.createProject(
                        Files.createDirectory(directory.resolve("second")).toFile(),
                        BufferedImage.class);
        var selected = new ProjectInferenceScope(Mode.ANNOTATIONS_BY_CLASS, Optional.of("Tissue"));
        selected.save(first);
        var reopened = ProjectIO.loadProject(first.getPath().toFile(), BufferedImage.class);
        assertEquals(selected, ProjectInferenceScope.read(reopened));
        assertEquals(ProjectInferenceScope.defaults(), ProjectInferenceScope.read(second));
        var slide = new ProjectInferenceScope(Mode.WHOLE_SLIDE, Optional.of("Tissue"));
        slide.save(reopened);
        assertTrue(slide.selectedClass().isEmpty());
        assertEquals(
                slide,
                ProjectInferenceScope.read(
                        ProjectIO.loadProject(first.getPath().toFile(), BufferedImage.class)));
    }

    @Test
    void previewCountsUseTheSameExactClassRuleAndDiscoverOtherOrdinaryClasses() {
        var hierarchy = new PathObjectHierarchy();
        var tissue =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 10, 10, null),
                        PathClass.fromString("Tissue"));
        var other =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(20, 0, 10, 10, null),
                        PathClass.fromString("Tissue: Other"));
        var marked =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(40, 0, 10, 10, null), tissue.getPathClass());
        marked.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var output =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(60, 0, 10, 10, null), tissue.getPathClass());
        output.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "invalid output");
        hierarchy.addObjects(List.of(tissue, other, marked, output));
        var selected = new ProjectInferenceScope(Mode.ANNOTATIONS_BY_CLASS, Optional.of("Tissue"));
        var capture = ProjectSummaryData.captureViewerHierarchy(hierarchy, selected);
        assertEquals(
                List.of(tissue.getID().toString()),
                capture.rois().stream().map(ProjectSummaryData.DetachedRoi::roiId).toList());
        assertEquals(Set.of("Tissue", "Tissue: Other"), capture.annotationClasses());
        assertFalse(tissue.isLocked());
        assertTrue(
                ProjectSummaryData.captureViewerHierarchy(
                                hierarchy,
                                new ProjectInferenceScope(
                                        Mode.ANNOTATIONS_BY_CLASS, Optional.of("Missing")))
                        .rois()
                        .isEmpty());
    }
}
