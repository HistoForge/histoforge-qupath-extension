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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Projects;

class ProjectInferenceSettingsTest {

    @TempDir Path temporary;

    @Test
    void customChoicesPersistAcrossQuPathProjectReopenWithoutLeakingToAnotherProject()
            throws Exception {
        Project<BufferedImage> first =
                Projects.createProject(
                        Files.createDirectory(temporary.resolve("first")).toFile(),
                        BufferedImage.class);
        Project<BufferedImage> second =
                Projects.createProject(
                        Files.createDirectory(temporary.resolve("second")).toFile(),
                        BufferedImage.class);
        Map<String, Map<String, Integer>> choices =
                Map.of("owner/module", Map.of("cells", 0, "nuclei", 96));
        first.getMetadata().put("unrelated", "kept");

        assertTrue(ProjectInferenceSettings.save(first, first, choices).isEmpty());
        Project<BufferedImage> reopened =
                ProjectIO.loadProject(first.getPath().toUri(), BufferedImage.class);

        assertEquals(choices, ProjectInferenceSettings.read(reopened));
        assertEquals("kept", reopened.getMetadata().get("unrelated"));
        assertTrue(ProjectInferenceSettings.read(second).isEmpty());
        assertEquals(
                "{\"owner/module\":{\"cells\":0,\"nuclei\":96}}",
                reopened.getMetadata().get(HistoforgeKeys.INFERENCE_SETTINGS));

        assertTrue(
                ProjectInferenceSettings.save(reopened, reopened, Map.of("owner/module", Map.of()))
                        .isEmpty());
        assertFalse(reopened.getMetadata().containsKey(HistoforgeKeys.INFERENCE_SETTINGS));
        assertTrue(
                ProjectInferenceSettings.read(
                                ProjectIO.loadProject(first.getPath().toUri(), BufferedImage.class))
                        .isEmpty());
    }

    @Test
    void projectSwitchRejectsSaveBeforeAnyMutationOrSync() throws Exception {
        Project<?> openedFor = project();
        Project<?> current = project();
        Map<String, Map<String, Integer>> choices = Map.of("owner/module", Map.of("cells", 32));

        assertThrows(
                IllegalStateException.class,
                () -> ProjectInferenceSettings.save(openedFor, current, choices));

        assertTrue(openedFor.getMetadata().isEmpty());
        assertTrue(current.getMetadata().isEmpty());
        verify(openedFor, never()).syncChanges();
        verify(current, never()).syncChanges();
    }

    @Test
    void syncFailureKeepsIntendedChoicesForExplicitRetry() throws Exception {
        Project<?> project = project();
        IOException failure = new IOException("disk full");
        doThrow(failure).when(project).syncChanges();
        Map<String, Map<String, Integer>> choices = Map.of("owner/module", Map.of("cells", 48));

        assertEquals(
                failure, ProjectInferenceSettings.save(project, project, choices).orElseThrow());
        assertEquals(choices, ProjectInferenceSettings.read(project));
    }

    @Test
    void persistedBoundaryRejectsInvalidKeysAndNonintegerOrNegativeHalo() {
        Project<?> project = project();
        for (String invalid :
                new String[] {
                    "[]",
                    "{\"bare\":{\"cells\":1}}",
                    "{\"owner/module\":{\"../cells\":1}}",
                    "{\"owner/module\":{\"cells\":-1}}",
                    "{\"owner/module\":{\"cells\":1.5}}",
                    "{\"owner/module\":{\"cells\":2147483648}}",
                    "{\"owner/module\":{\"cells\":\"32\"}}",
                    "{\"owner/module\":{\"cells\":null}}"
                }) {
            project.getMetadata().put(HistoforgeKeys.INFERENCE_SETTINGS, invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProjectInferenceSettings.read(project),
                    invalid);
        }
        project.getMetadata().clear();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProjectInferenceSettings.save(
                                project, project, Map.of("owner/module", Map.of("cells", -1))));
        assertTrue(project.getMetadata().isEmpty());
    }

    @Test
    void readIsADetachedImmutableSnapshot() {
        Project<?> project = project();
        ProjectInferenceSettings.save(
                project, project, Map.of("owner/module", Map.of("cells", 32)));
        var captured = ProjectInferenceSettings.read(project);
        ProjectInferenceSettings.save(
                project, project, Map.of("owner/module", Map.of("cells", 64)));
        assertEquals(32, captured.get("owner/module").get("cells"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> captured.get("owner/module").put("cells", 99));
    }

    private static Project<?> project() {
        Project<?> project = mock(Project.class);
        when(project.getMetadata()).thenReturn(new HashMap<>());
        return project;
    }
}
