package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

class ProjectMetadataEditorTest {

    @Test
    void successfulEditPersistsOnce() throws Exception {
        Project<?> project = project();
        ProjectImageEntry<?> first = entry(new HashMap<>());
        ProjectImageEntry<?> second = entry(new HashMap<>(Map.of("module", "old")));

        assertTrue(
                ProjectMetadataEditor.setAndSync(
                                project, List.of(first, second), "module", "histoforge/leydig")
                        .isEmpty());

        assertEquals("histoforge/leydig", first.getMetadata().get("module"));
        assertEquals("histoforge/leydig", second.getMetadata().get("module"));
        verify(project).syncChanges();
    }

    @Test
    void incompletePersistenceRetainsIntendedValuesForSafeRetry() throws Exception {
        Project<?> project = project();
        IOException failure = new IOException("disk full");
        doThrow(failure).when(project).syncChanges();
        ProjectImageEntry<?> first = entry(new HashMap<>());
        ProjectImageEntry<?> second = entry(new HashMap<>(Map.of("module", "old")));

        Optional<Throwable> persistenceFailure =
                ProjectMetadataEditor.setAndSync(
                        project, List.of(first, second), "module", "histoforge/leydig");

        assertEquals(failure, persistenceFailure.orElseThrow());
        assertEquals("histoforge/leydig", first.getMetadata().get("module"));
        assertEquals("histoforge/leydig", second.getMetadata().get("module"));
    }

    @Test
    void failedMutationRestoresEntriesAlreadyChangedAndDoesNotSync() throws Exception {
        Project<?> project = project();
        ProjectImageEntry<?> first = entry(new HashMap<>());
        ProjectImageEntry<?> second = mock(ProjectImageEntry.class);
        when(second.getMetadata()).thenThrow(new IllegalStateException("metadata unavailable"));

        assertThrows(
                IllegalStateException.class,
                () ->
                        ProjectMetadataEditor.setAndSync(
                                project, List.of(first, second), "module", "histoforge/leydig"));

        assertFalse(first.getMetadata().containsKey("module"));
        verify(project, never()).syncChanges();
    }

    @SuppressWarnings("unchecked")
    private static Project<BufferedImage> project() {
        return mock(Project.class);
    }

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(Map<String, String> metadata) {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getMetadata()).thenReturn(metadata);
        return entry;
    }
}
