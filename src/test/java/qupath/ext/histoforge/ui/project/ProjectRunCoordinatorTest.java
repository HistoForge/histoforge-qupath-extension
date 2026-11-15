package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.pipeline.BatchOrchestrator;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

class ProjectRunCoordinatorTest {

    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";

    @Test
    void buildsAllModelFillWithCapturedRoisAndPreference() {
        var action =
                assertInstanceOf(
                        ActionSelectionCodec.Fill.class,
                        ProjectBatchLauncher.action(
                                row(),
                                List.of(),
                                ProjectRunCoordinator.RequestedAction.FILL,
                                Mode.HISTOFORGE_ROIS,
                                true));

        assertTrue(action.removeOverlappingSameClassDuplicates());
        var all = assertInstanceOf(ActionSelectionCodec.AllModels.class, action.work());
        assertEquals(
                Set.of(ROI_A, ROI_B),
                assertInstanceOf(ActionSelectionCodec.RoiRegions.class, all.regions()).roiIds());
    }

    @Test
    void buildsSelectedRedoInRecipeOrderWithExplicitTargets() {
        var action =
                assertInstanceOf(
                        ActionSelectionCodec.Redo.class,
                        ProjectBatchLauncher.action(
                                row(),
                                List.of("parents", "children"),
                                ProjectRunCoordinator.RequestedAction.REDO,
                                Mode.HISTOFORGE_ROIS,
                                false));

        assertFalse(action.removeOverlappingSameClassDuplicates());
        var selected = assertInstanceOf(ActionSelectionCodec.SelectedModels.class, action.work());
        assertEquals(
                List.of("parents", "children"),
                selected.steps().stream().map(ActionSelectionCodec.StepTargets::stepId).toList());
        for (var step : selected.steps()) {
            assertEquals(
                    Set.of(ROI_A, ROI_B),
                    step.targets().selectors().stream()
                            .map(ActionSelectionCodec.RoiTarget.class::cast)
                            .map(ActionSelectionCodec.RoiTarget::roiId)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void metricsActionCarriesOnlyTheRegionSelection() {
        var action =
                assertInstanceOf(
                        ActionSelectionCodec.Metrics.class,
                        ProjectBatchLauncher.action(
                                row(),
                                List.of("ignored"),
                                ProjectRunCoordinator.RequestedAction.METRICS,
                                Mode.HISTOFORGE_ROIS,
                                true));

        assertEquals(
                Set.of(ROI_A, ROI_B),
                assertInstanceOf(ActionSelectionCodec.RoiRegions.class, action.targets()).roiIds());
    }

    @Test
    void buildsWholeSlideActionsWithoutRoiTargets() {
        var fill =
                assertInstanceOf(
                        ActionSelectionCodec.Fill.class,
                        ProjectBatchLauncher.action(
                                row(Set.of()),
                                List.of(),
                                ProjectRunCoordinator.RequestedAction.FILL,
                                Mode.WHOLE_SLIDE,
                                true));
        var all = assertInstanceOf(ActionSelectionCodec.AllModels.class, fill.work());
        var slide = assertInstanceOf(ActionSelectionCodec.SlideRegion.class, all.regions());
        assertInstanceOf(ActionSelectionCodec.FullSlide.class, slide.region());

        var redo =
                assertInstanceOf(
                        ActionSelectionCodec.Redo.class,
                        ProjectBatchLauncher.action(
                                row(Set.of()),
                                List.of("cells"),
                                ProjectRunCoordinator.RequestedAction.REDO,
                                Mode.WHOLE_SLIDE,
                                false));
        var selected = assertInstanceOf(ActionSelectionCodec.SelectedModels.class, redo.work());
        assertEquals(1, selected.steps().size());
        assertEquals(
                List.of(new ActionSelectionCodec.SlideTarget()),
                selected.steps().getFirst().targets().selectors());

        var metrics =
                assertInstanceOf(
                        ActionSelectionCodec.Metrics.class,
                        ProjectBatchLauncher.action(
                                row(Set.of()),
                                List.of(),
                                ProjectRunCoordinator.RequestedAction.METRICS,
                                Mode.WHOLE_SLIDE,
                                true));
        assertInstanceOf(ActionSelectionCodec.SlideRegion.class, metrics.targets());
    }

    @Test
    void roiScopesRequireMatchingAnnotationsButWholeSlideDoesNot() {
        ImageRowModel withoutRois = row(Set.of());

        assertEquals(
                List.of(withoutRois),
                ProjectRunCoordinator.rowsWithoutRequiredRois(
                        List.of(withoutRois), Mode.HISTOFORGE_ROIS));
        assertEquals(
                List.of(withoutRois),
                ProjectRunCoordinator.rowsWithoutRequiredRois(
                        List.of(withoutRois), Mode.ANNOTATIONS_BY_CLASS));
        assertTrue(
                ProjectRunCoordinator.rowsWithoutRequiredRois(
                                List.of(withoutRois), Mode.WHOLE_SLIDE)
                        .isEmpty());
    }

    @Test
    void emptyAnnotationPreviewNamesTheImageAndExplainsExplicitRefresh() {
        var empty = row(Set.of());
        when(empty.entry.getImageName()).thenReturn("Slide without tissue");
        var selected =
                new ImageRowModel(
                        empty.entry,
                        "group",
                        "module",
                        "histoforge/module",
                        Set.of(),
                        "—",
                        "",
                        null,
                        null,
                        Optional.of("Tissue"));
        String message =
                ProjectRunCoordinator.missingRegionsMessage(
                        List.of(selected), Mode.ANNOTATIONS_BY_CLASS);
        assertTrue(message.contains("class \"Tissue\""));
        assertTrue(message.contains("Slide without tissue"));
        assertTrue(message.contains("last project refresh"));
        assertTrue(message.contains("Save Project & Refresh"));
        assertTrue(message.contains("No whole-slide processing will be substituted"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectBatchLauncher.regions(selected, Mode.ANNOTATIONS_BY_CLASS));
    }

    @Test
    void wholeSlideProgressKeepsTheHierarchyConflictVisible() {
        var progress =
                new BatchOrchestrator.BatchProgress(
                        0,
                        1,
                        0,
                        "image",
                        "slide",
                        BatchOrchestrator.BatchPhase.RUNNING,
                        Optional.empty(),
                        0,
                        Optional.empty());

        assertTrue(ProjectBatchSession.progressLabel(progress, true).contains("hierarchy edits"));
        assertFalse(ProjectBatchSession.progressLabel(progress, false).contains("hierarchy edits"));
    }

    @Test
    void persistedWholeSlideRecoveryKeepsTheHierarchyConflictVisible() {
        PersistedActionRecovery.Item item = mock(PersistedActionRecovery.Item.class);
        when(item.phase()).thenReturn(LargeActionPhase.EXECUTING);
        when(item.moduleRef()).thenReturn("histoforge/module");
        when(item.wholeSlide()).thenReturn(true);

        assertTrue(
                ProjectRecoverySession.recoveryProgressLabel("image", item)
                        .contains("hierarchy edits"));
    }

    @Test
    void recoveryRootIsAStablePrivateSiblingOfTheSavedProject() throws Exception {
        Project<?> project = mock(Project.class);
        Path projectFile = Path.of("build", "test-project", "study.qpproj").toAbsolutePath();
        when(project.getPath()).thenReturn(projectFile);

        assertEquals(
                projectFile.getParent().resolve(".study.qpproj.histoforge").resolve("recovery"),
                ProjectRunCoordinator.recoveryRoot(project));
    }

    @Test
    void recoveryRootRequiresASavedProjectPath() {
        Project<?> project = mock(Project.class);
        when(project.getPath()).thenReturn(null);

        assertThrows(IOException.class, () -> ProjectRunCoordinator.recoveryRoot(project));
    }

    @Test
    void filtersPersistedRecoveryToTheExplicitlySelectedImages() {
        PersistedActionRecovery.Item first = mock(PersistedActionRecovery.Item.class);
        PersistedActionRecovery.Item second = mock(PersistedActionRecovery.Item.class);
        when(first.imageId()).thenReturn("first");
        when(second.imageId()).thenReturn("second");

        assertEquals(
                List.of(second),
                ProjectPersistedRecoveryGate.selectedUnresolved(
                        List.of(row("second")), List.of(first, second)));
    }

    @Test
    void recoveryEntryUsesTheCapturedBuildWithoutInstalledModuleOrCloudState() {
        ClientBuildIdentity capturedBuild = mock(ClientBuildIdentity.class);
        PersistedActionRecovery.Item item = mock(PersistedActionRecovery.Item.class);
        when(item.clientBuild()).thenReturn(capturedBuild);

        var availability = ProjectPersistedRecoveryGate.availability(item, capturedBuild);

        assertTrue(availability.canResume());
        assertEquals("", availability.unavailableReason());
        verify(item).clientBuild();
        verifyNoMoreInteractions(item);
    }

    @Test
    void recoveryEntryStillRequiresTheExactCapturedBuild() {
        ClientBuildIdentity capturedBuild = mock(ClientBuildIdentity.class);
        ClientBuildIdentity anotherBuild = mock(ClientBuildIdentity.class);
        PersistedActionRecovery.Item item = mock(PersistedActionRecovery.Item.class);
        when(item.clientBuild()).thenReturn(capturedBuild);

        var availability = ProjectPersistedRecoveryGate.availability(item, anotherBuild);

        assertFalse(availability.canResume());
        assertEquals(
                "this unfinished analysis requires the exact extension build that started it",
                availability.unavailableReason());
    }

    @SuppressWarnings("unchecked")
    private static ImageRowModel row() {
        return row("image");
    }

    @SuppressWarnings("unchecked")
    private static ImageRowModel row(String imageId) {
        return row(imageId, Set.of(ROI_B, ROI_A));
    }

    private static ImageRowModel row(Set<String> roiIds) {
        return row("image", roiIds);
    }

    @SuppressWarnings("unchecked")
    private static ImageRowModel row(String imageId, Set<String> roiIds) {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn(imageId);
        return new ImageRowModel(
                entry, "group", "module", "histoforge/module", roiIds, "—", "", null, null);
    }
}
