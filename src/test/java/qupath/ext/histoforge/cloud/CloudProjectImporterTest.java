package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

class CloudProjectImporterTest {

    @Test
    void nullProjectEntryIsAFailedAddAndIsNotReportedAsSuccess() throws Exception {
        Project<BufferedImage> project = project();
        when(project.addImage(any())).thenReturn(null);
        var slide = new CloudProjectImporter.Slide("study/a.svs", "a.svs");

        CloudProjectImporter.Result result =
                CloudProjectImporter.add(project, "slides", List.of(slide));

        assertTrue(result.added().isEmpty());
        assertEquals(List.of(slide), result.failures().stream().map(f -> f.slide()).toList());
        assertTrue(result.persistenceFailure().isEmpty());
        verify(project, never()).syncChanges();
    }

    @Test
    void successfulAddsAreNamedAndPersistedOnce() throws Exception {
        Project<BufferedImage> project = project();
        ProjectImageEntry<BufferedImage> entry = entry();
        when(project.addImage(any())).thenReturn(entry);
        var slide = new CloudProjectImporter.Slide("study/a.svs", "a.svs");

        CloudProjectImporter.Result result =
                CloudProjectImporter.add(project, "slides", List.of(slide));

        assertEquals(List.of(slide), result.added());
        assertTrue(result.failures().isEmpty());
        assertTrue(result.persistenceFailure().isEmpty());
        verify(entry).setImageName("a.svs");
        verify(project).syncChanges();
    }

    @Test
    void persistenceFailureIsSeparateFromInMemoryAddTruth() throws Exception {
        Project<BufferedImage> project = project();
        when(project.addImage(any())).thenReturn(entry());
        IOException saveFailure = new IOException("disk full");
        org.mockito.Mockito.doThrow(saveFailure).when(project).syncChanges();
        var slide = new CloudProjectImporter.Slide("study/a.svs", "a.svs");

        CloudProjectImporter.Result result =
                CloudProjectImporter.add(project, "slides", List.of(slide));

        assertEquals(List.of(slide), result.added());
        assertTrue(result.failures().isEmpty());
        assertEquals(saveFailure, result.persistenceFailure().orElseThrow());
    }

    @Test
    void namingFailureStillPersistsAndReportsTheAddedEntry() throws Exception {
        Project<BufferedImage> project = project();
        ProjectImageEntry<BufferedImage> entry = entry();
        when(project.addImage(any())).thenReturn(entry);
        org.mockito.Mockito.doThrow(new IllegalStateException("name rejected"))
                .when(entry)
                .setImageName("a.svs");
        var slide = new CloudProjectImporter.Slide("study/a.svs", "a.svs");

        CloudProjectImporter.Result result =
                CloudProjectImporter.add(project, "slides", List.of(slide));

        assertEquals(List.of(slide), result.added());
        assertEquals(List.of(slide), result.failures().stream().map(f -> f.slide()).toList());
        verify(project).syncChanges();
    }

    @SuppressWarnings("unchecked")
    private static Project<BufferedImage> project() {
        return mock(Project.class);
    }

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry() {
        return mock(ProjectImageEntry.class);
    }
}
