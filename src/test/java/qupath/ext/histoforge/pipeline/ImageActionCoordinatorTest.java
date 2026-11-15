package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.eligible;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

class ImageActionCoordinatorTest {

    private final ImageActionRegistry registry = new ImageActionRegistry();
    private final ImageLifecycle lifecycle = mock(ImageLifecycle.class);
    private final FiniteSourceProvisioner sourceProvisioner = mock(FiniteSourceProvisioner.class);
    private final ImageActionExecutor finite = mock(ImageActionExecutor.class);
    private final ImageMetricsExecutor metrics = mock(ImageMetricsExecutor.class);
    private final RecoverableImageActionExecutor recoverable =
            mock(RecoverableImageActionExecutor.class);
    private final LargeActionHandleStore handles = mock(LargeActionHandleStore.class);
    private final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
    private final ImageLifecycle.WorkingImage working = mock(ImageLifecycle.WorkingImage.class);
    private ImageData<BufferedImage> image;
    private ImageActionExecutor.Request request;
    private ImageActionCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        image =
                RecipeExecutorTestData.imageData(
                        "coordinated-image",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B);
        ModuleSnapshot module = splitProtocolModule();
        ActionSelectionCodec.ImageAction action = selectedTargets();
        when(entry.getID()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
        when(working.imageData()).thenReturn(image);
        when(working.sourceRevision()).thenReturn(RecipeExecutorTestData.sourceRevision());
        when(working.sourcePyramid()).thenReturn(RecipeExecutorTestData.capturedSourcePyramid());
        when(working.eligibleOriginalSource()).thenReturn(Optional.empty());
        when(lifecycle.openWorkingImage(any(), any(), any())).thenReturn(working);
        when(recoverable.recovery()).thenReturn(mock(RecoverableActionRecovery.class));
        request =
                new ImageActionExecutor.Request(
                        RecipeExecutorTestData.ACTION_ID,
                        RecipeExecutorTestData.clientBuild(1_000_000),
                        entry,
                        module,
                        action);
        coordinator =
                new ImageActionCoordinator(
                        registry,
                        lifecycle,
                        RecipeExecutorTestData.sourcePlanningLimits(),
                        handles,
                        sourceProvisioner,
                        finite,
                        recoverable,
                        metrics);
        // Constructor wiring is not model execution or a recovery attempt.
        clearInvocations(recoverable);
    }

    @AfterEach
    void tearDown() throws Exception {
        image.close();
    }

    @Test
    void classSelectionLocksBeforeAnyDispatchAndPreservesFullSelectionInEveryGroup()
            throws Exception {
        image.getHierarchy()
                .getAnnotationObjects()
                .forEach(
                        object -> {
                            object.getMetadata().remove(HistoforgeKeys.ROI_TYPE);
                            object.setPathClass(PathClass.fromString("Tissue"));
                            object.setLocked(false);
                        });
        request =
                new ImageActionExecutor.Request(
                        request.actionId(),
                        request.clientBuild(),
                        entry,
                        allRecoverableModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)),
                        Optional.of("Tissue"));
        when(lifecycle.commit(working))
                .thenReturn(
                        new ImageLifecycle.CommitResult(
                                RecipeExecutorTestData.sourceRevision().imageDataRevision(),
                                false,
                                false,
                                Optional.empty()));
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenAnswer(
                        call -> {
                            verify(lifecycle).commit(working);
                            ImageActionSubsetPlanner.Subset subset = call.getArgument(2);
                            assertEquals(2, subset.execution().selectionRegions().size());
                            assertEquals(1, subset.execution().targetRegions().size());
                            assertEquals(1, subset.boundaryDigests().size());
                            org.junit.jupiter.api.Assertions.assertTrue(
                                    image.getHierarchy().getAnnotationObjects().stream()
                                            .allMatch(object -> object.isLocked()));
                            return new RecoverableImageActionExecutor.CoordinatedAttempt(
                                    new RecoverableActionAttempt.Completed(successful()));
                        });
        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));
        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                completed.result().outcome(),
                completed.toString());
        verify(recoverable, times(2)).execute(any(), any(), any(), eq(Optional.empty()), any());
    }

    @Test
    void annotationSaveFailurePreventsSourcePreparationAndBothExecutors() throws Exception {
        image.getHierarchy()
                .getAnnotationObjects()
                .forEach(
                        object -> {
                            object.getMetadata().remove(HistoforgeKeys.ROI_TYPE);
                            object.setPathClass(PathClass.fromString("Tissue"));
                            object.setLocked(false);
                        });
        request =
                new ImageActionExecutor.Request(
                        request.actionId(),
                        request.clientBuild(),
                        entry,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)),
                        Optional.of("Tissue"));
        when(lifecycle.commit(working)).thenThrow(new IOException("test failed preparation save"));
        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));
        assertEquals(RunResults.ImageOutcomeType.FAILED, completed.result().outcome());
        verifyNoInteractions(sourceProvisioner, finite, recoverable, metrics);
    }

    @Test
    void finiteAndRecoverableTargetsUseOneReservationAndAggregateAfterBothTerminal()
            throws Exception {
        RunResults.ImageRunResult finiteResult = successful();
        RunResults.ImageRunResult durableResult = successful();
        when(finite.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(finiteResult);
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(durableResult)));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                completed.result().outcome());
        verify(sourceProvisioner, never()).open();
        verify(finite).execute(any(), any(), any(), eq(Optional.empty()), any());
        verify(recoverable).execute(any(), any(), any(), eq(Optional.empty()), any());
    }

    @Test
    void recoverablePreHandleCapacityFailureRemainsTypedAtTheCoordinatorBoundary()
            throws Exception {
        when(finite.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(successful());
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenThrow(
                        new LocalCapacityExceededException(
                                "max_hierarchy_geometry_vertices", 7, 6));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        RunResults.FailureDetail failure =
                completed.result().scopes().stream()
                        .flatMap(scope -> scope.failure().stream())
                        .findFirst()
                        .orElseThrow();
        assertEquals(RunResults.FailureType.LOCAL_CAPACITY, failure.type());
    }

    @Test
    void cancellationAfterFiniteCommitRetainsItsSaveAndDoesNotStartLaterTarget() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        RunResults.ImageRunResult saved =
                new RunResults.ImageRunResult(
                        RecipeExecutorTestData.IMAGE_ID,
                        RunResults.ImageOutcomeType.SUCCEEDED,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.empty());
        when(finite.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenAnswer(
                        invocation -> {
                            stop.set(true);
                            return saved;
                        });

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, stop::get));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        verify(recoverable, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void allFiniteActionUsesTheExactActionWideCapturedSubset() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        ActionSelectionCodec.ImageAction action =
                RecipeExecutorTestData.allModels(
                        false, Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
        request = request(module, action);
        when(finite.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(successful());

        assertInstanceOf(
                ImageActionAttempt.Completed.class, coordinator.execute(request, () -> false));

        verify(finite)
                .execute(
                        any(),
                        any(),
                        argThat(
                                subset ->
                                        subset.execution().targetRegions().size() == 2
                                                && subset.sources().targets().size() == 2),
                        eq(Optional.empty()),
                        any());
        verify(sourceProvisioner, never()).open();
        verify(recoverable, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void metricsOnlyActionUsesTheMetricsOwnerExactlyOnce() throws Exception {
        request =
                request(
                        RecipeExecutorTestData.fieldMetricOnlyModule(),
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(RecipeExecutorTestData.ROI_A))));
        when(metrics.execute(eq(request), any(ImageActionRegistry.Reservation.class), any(), any()))
                .thenReturn(successful());

        assertInstanceOf(
                ImageActionAttempt.Completed.class, coordinator.execute(request, () -> false));

        verify(metrics)
                .execute(eq(request), any(ImageActionRegistry.Reservation.class), any(), any());
        verify(lifecycle, never()).openWorkingImage(any(), any(), any());
        verify(sourceProvisioner, never()).open();
        verify(recoverable, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void actionWideSourcePlanningRejectionIsAnInvalidRequestRatherThanCorruptState()
            throws Exception {
        // An oversized source envelope now uses bounded uploads. A genuinely inadmissible model
        // input still fails before IO: this model's target exceeds finite limits with no profile.
        var module = request.module();
        var models = new LinkedHashMap<>(module.activatedModels());
        var second = models.get("second");
        models.put(
                "second",
                new ModelSnapshot(
                        second.architecture(),
                        second.gpu(),
                        second.modelConfigDigest(),
                        second.input(),
                        second.output(),
                        second.capabilities(),
                        Optional.empty(),
                        second.artifactDigests()));
        request =
                request(
                        new ModuleSnapshot(
                                module.deploymentId(),
                                module.module(),
                                module.exactRecipe(),
                                module.scriptSetDigest(),
                                models,
                                module.scripts(),
                                module.metrics()),
                        selectedTargets());

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(RunResults.ImageOutcomeType.FAILED, completed.result().outcome());
        assertEquals(
                RunResults.FailureType.INVALID_REQUEST,
                completed.result().imageFailures().getFirst().type());
        verify(finite, never()).execute(any(), any(), any(), any(), any());
        verify(recoverable, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void projectImageOpenFailureIsClassifiedAsPersistence() throws Exception {
        when(lifecycle.openWorkingImage(any(), any(), any()))
                .thenThrow(new IOException("project image read failed"));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.FailureType.PERSISTENCE,
                completed.result().imageFailures().getFirst().type());
    }

    @Test
    void sharedSourcePreparationFailureIsClassifiedAsExtraction() throws Exception {
        SharedOriginalFixture fixture = configureSharedOriginalCapture();
        when(fixture.session().prepareOriginal(any(), any(), eq(image), eq(working), any()))
                .thenThrow(new IOException("source materialization failed"));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.FailureType.EXTRACTION,
                completed.result().imageFailures().getFirst().type());
        verify(fixture.session(), never()).publishOriginal(any(), any(), any(), any());
        verify(fixture.session()).close();
    }

    @Test
    void sharedSourcePreparationRuntimeFailureIsClassifiedAsExtraction() throws Exception {
        SharedOriginalFixture fixture = configureSharedOriginalCapture();
        when(fixture.session().prepareOriginal(any(), any(), eq(image), eq(working), any()))
                .thenThrow(new IllegalStateException("source materialization failed"));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.FailureType.EXTRACTION,
                completed.result().imageFailures().getFirst().type());
        verify(fixture.session()).close();
    }

    @Test
    void sharedSourcePublicationFailureIsClassifiedAsSubmission() throws Exception {
        SharedOriginalFixture fixture = configureSharedOriginalCapture();
        when(fixture.session().publishOriginal(any(), any(), eq(fixture.prepared()), any()))
                .thenThrow(new IOException("source upload failed"));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.FailureType.SUBMISSION,
                completed.result().imageFailures().getFirst().type());
        verify(fixture.session()).close();
    }

    @Test
    void sharedSourcePublicationRuntimeFailureIsClassifiedAsSubmission() throws Exception {
        SharedOriginalFixture fixture = configureSharedOriginalCapture();
        when(fixture.session().publishOriginal(any(), any(), eq(fixture.prepared()), any()))
                .thenThrow(new IllegalStateException("source upload failed"));

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.FailureType.SUBMISSION,
                completed.result().imageFailures().getFirst().type());
        verify(fixture.session()).close();
    }

    @Test
    void suspendedTargetRetainsAggregateUntilExplicitResume() throws Exception {
        RunResults.ImageRunResult finiteResult = successful();
        RunResults.ImageRunResult durableResult = successful();
        when(finite.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(finiteResult);
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenAnswer(
                        invocation -> {
                            ImageActionRegistry.Reservation reservation = invocation.getArgument(1);
                            ImageActionSubsetPlanner.Subset subset = invocation.getArgument(2);
                            LargeActionHandle handle = mock(LargeActionHandle.class);
                            when(handle.imageId()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
                            RecoverableActionAttempt.ResumeOwnership ownership =
                                    new RecoverableActionAttempt.ResumeOwnership(
                                            handle,
                                            Optional.of(subset.execution()),
                                            List.of(),
                                            reservation,
                                            Optional.of(new IOException("download interrupted")));
                            RecoverableActionAttempt.Suspended suspended =
                                    mock(RecoverableActionAttempt.Suspended.class);
                            when(suspended.claimForResume()).thenReturn(ownership);
                            return new RecoverableImageActionExecutor.CoordinatedAttempt(suspended);
                        });
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(durableResult)));

        ImageActionAttempt initial = coordinator.execute(request, () -> false);
        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(ImageActionAttempt.Suspended.class, initial, initial::toString);
        assertEquals(1, suspended.handles().size());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var competing =
                    waiter.submit(
                            () -> {
                                try (var ignored =
                                        registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
                                    return true;
                                }
                            });
            assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
            competing.cancel(true);
        }

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.resume(suspended, () -> false));

        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                completed.result().outcome());
        try (var ignored = registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
            assertEquals(RecipeExecutorTestData.IMAGE_ID, ignored.imageId());
        }
    }

    @Test
    void suspensionDefersLaterCrossModeTargetUntilTheFirstTargetIsTerminal() throws Exception {
        ModuleSnapshot module = allRecoverableModule();
        image.getHierarchy()
                .addObject(
                        RecipeExecutorTestData.attributed(
                                "50000000-0000-4000-8000-000000000099",
                                "Existing",
                                RecipeExecutorTestData.square(10, 10, 20, 20),
                                "parent",
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Slide())));
        request =
                request(
                        module,
                        new ActionSelectionCodec.Redo(
                                new ActionSelectionCodec.AllModels(
                                        new ActionSelectionCodec.RoiRegions(
                                                Set.of(
                                                        RecipeExecutorTestData.ROI_A,
                                                        RecipeExecutorTestData.ROI_B))),
                                true));
        LargeActionHandle first =
                handle("50000000-0000-4000-8000-000000000001", LargeActionPhase.EXECUTING);
        LargeActionHandle second =
                handle("50000000-0000-4000-8000-000000000002", LargeActionPhase.EXECUTING);
        stubSuspensions(first, second);
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())),
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())));

        ImageActionAttempt.Suspended initial =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));
        assertEquals(List.of(first), initial.handles());
        assertEquals(2, initial.unresolvedTargetCount());
        verify(recoverable, times(1)).execute(any(), any(), any(), eq(Optional.empty()), any());

        ImageActionAttempt.Suspended remaining =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.resume(initial, () -> false));

        assertEquals(List.of(second), remaining.handles());
        assertEquals(1, remaining.unresolvedTargetCount());
        verify(recoverable, times(2)).execute(any(), any(), any(), eq(Optional.empty()), any());
        verify(recoverable, times(1))
                .resume(any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any());
        assertReservationBlocked();

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.resume(remaining, () -> false));
        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                completed.result().outcome());
        verify(recoverable, times(2))
                .resume(any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any());
        assertReservationAvailable();
    }

    @Test
    void lostHandleOnCoordinatedResumeFailsThatTargetAndRunsTheDeferredTarget() throws Exception {
        request = request(allRecoverableModule(), selectedTargets());
        LargeActionHandle first =
                handle("50000000-0000-4000-8000-000000000003", LargeActionPhase.EXECUTING);
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenThrow(
                        new UnrecoverableRecoveryStateException("retained handle file is missing"));

        AtomicInteger starts = new AtomicInteger();
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenAnswer(
                        invocation -> {
                            if (starts.getAndIncrement() == 0) {
                                ImageActionRegistry.Reservation reservation =
                                        invocation.getArgument(1);
                                ImageActionSubsetPlanner.Subset subset = invocation.getArgument(2);
                                RecoverableActionAttempt.Suspended target =
                                        new RecoverableActionAttempt.Suspended(
                                                first,
                                                subset.execution(),
                                                List.of(),
                                                new IOException("model run response was lost"),
                                                handles,
                                                reservation);
                                return new RecoverableImageActionExecutor.CoordinatedAttempt(
                                        target);
                            }
                            return new RecoverableImageActionExecutor.CoordinatedAttempt(
                                    new RecoverableActionAttempt.Completed(successful()));
                        });

        ImageActionAttempt initial = coordinator.execute(request, () -> false);
        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(ImageActionAttempt.Suspended.class, initial, initial::toString);
        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.resume(suspended, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(
                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                completed.result().scopes().stream()
                        .flatMap(scope -> scope.failure().stream())
                        .map(RunResults.FailureDetail::type)
                        .filter(RunResults.FailureType.CORRUPT_IMAGE_STATE::equals)
                        .findFirst()
                        .orElseThrow());
        verify(recoverable, times(2)).execute(any(), any(), any(), eq(Optional.empty()), any());
        assertReservationAvailable();
    }

    @Test
    void failedAbandonCanBeRetriedWithoutLosingTheReservation() throws Exception {
        request = request(allRecoverableModule(), selectedTargets());
        String firstGeneration = "50000000-0000-4000-8000-000000000011";
        LargeActionHandle first = handle(firstGeneration, LargeActionPhase.EXECUTING);
        LargeActionHandle firstCancelled = handle(firstGeneration, LargeActionPhase.CANCELLED);
        stubSuspensions(first);
        when(handles.load(first.actionId(), firstGeneration))
                .thenThrow(new IOException("temporary handle read failure"))
                .thenReturn(first);
        when(handles.abandon(first.actionId(), firstGeneration)).thenReturn(firstCancelled);

        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));

        assertThrows(IOException.class, () -> coordinator.abandon(suspended));
        assertReservationBlocked();

        ImageActionAttempt.Completed abandoned = coordinator.abandon(suspended);
        assertEquals(RunResults.ImageOutcomeType.CANCELLED, abandoned.result().outcome());
        verify(handles, times(1)).abandon(first.actionId(), firstGeneration);
        verify(recoverable, times(1)).execute(any(), any(), any(), eq(Optional.empty()), any());
        assertReservationAvailable();
    }

    @Test
    void lostHandleDuringAbandonmentFailsItAndCancelsTheUnstartedTarget() throws Exception {
        request = request(allRecoverableModule(), selectedTargets());
        String lostGeneration = "50000000-0000-4000-8000-000000000031";
        LargeActionHandle lost = handle(lostGeneration, LargeActionPhase.EXECUTING);
        stubSuspensions(lost);
        when(handles.load(lost.actionId(), lostGeneration))
                .thenThrow(new UnrecoverableRecoveryStateException("handle file is missing"));

        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));
        ImageActionAttempt.Completed completed = coordinator.abandon(suspended);

        assertEquals(RunResults.ImageOutcomeType.FAILED, completed.result().outcome());
        assertEquals(
                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                completed.result().scopes().stream()
                        .flatMap(scope -> scope.failure().stream())
                        .map(RunResults.FailureDetail::type)
                        .filter(RunResults.FailureType.CORRUPT_IMAGE_STATE::equals)
                        .findFirst()
                        .orElseThrow());
        assertEquals(
                1,
                completed.result().scopes().stream()
                        .filter(scope -> scope.type() == RunResults.ScopeOutcomeType.CANCELLED)
                        .count());
        verify(recoverable, times(1)).execute(any(), any(), any(), eq(Optional.empty()), any());
        assertReservationAvailable();
    }

    @Test
    void abandoningPendingTargetsStillRunsTheRequestedFinalMetricPass() throws Exception {
        request =
                request(
                        allRecoverableMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(
                                        RecipeExecutorTestData.ROI_A,
                                        RecipeExecutorTestData.ROI_B)));
        String firstGeneration = "50000000-0000-4000-8000-000000000021";
        LargeActionHandle first = handle(firstGeneration, LargeActionPhase.EXECUTING);
        LargeActionHandle firstCancelled = handle(firstGeneration, LargeActionPhase.CANCELLED);
        stubSuspensions(first);
        when(handles.load(first.actionId(), firstGeneration)).thenReturn(first);
        when(handles.abandon(first.actionId(), firstGeneration)).thenReturn(firstCancelled);
        when(metrics.execute(any(), any(), any(), any())).thenReturn(successful());

        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));
        ImageActionAttempt.Completed abandoned = coordinator.abandon(suspended);

        assertEquals(RunResults.ImageOutcomeType.CANCELLED, abandoned.result().outcome());
        verify(metrics)
                .execute(
                        any(),
                        any(),
                        argThat(
                                failures ->
                                        failures.keySet()
                                                .equals(
                                                        Set.of(
                                                                new ExecutionScopeCodec.Roi(
                                                                        RecipeExecutorTestData
                                                                                .ROI_A),
                                                                new ExecutionScopeCodec.Roi(
                                                                        RecipeExecutorTestData
                                                                                .ROI_B)))),
                        any());
        assertReservationAvailable();
    }

    @Test
    void actionWideOriginalIsPublishedOnceAndSharedByFiniteAndRecoverableTargets()
            throws Exception {
        SourcePlanningLimits originalFallback = new SourcePlanningLimits(1_000_000, 1, 8_000_000);
        coordinator =
                new ImageActionCoordinator(
                        registry,
                        lifecycle,
                        originalFallback,
                        handles,
                        sourceProvisioner,
                        finite,
                        recoverable,
                        metrics);
        RasterInputCodec.ActionSourceRevision sourceRevision = stableSourceRevision();
        when(working.sourceRevision()).thenReturn(sourceRevision);
        when(working.sourcePyramid()).thenReturn(sourcePyramid(sourceRevision));
        when(working.eligibleOriginalSource())
                .thenReturn(Optional.of(eligible(1_234, sourcePyramid(sourceRevision))));
        FiniteSourceProvisioner.Session session = mock(FiniteSourceProvisioner.Session.class);
        FiniteSourceProvisioner.PreparedOriginal prepared =
                mock(FiniteSourceProvisioner.PreparedOriginal.class);
        when(sourceProvisioner.open()).thenReturn(session);
        when(session.prepareOriginal(any(), any(), eq(image), eq(working), any()))
                .thenReturn(prepared);
        SealedOriginalSource shared = sharedOriginal();
        when(session.publishOriginal(any(), any(), eq(prepared), any())).thenReturn(shared);
        when(finite.execute(any(), any(), any(), eq(Optional.of(shared)), any()))
                .thenReturn(successful());
        when(recoverable.execute(any(), any(), any(), eq(Optional.of(shared)), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())));

        assertInstanceOf(
                ImageActionAttempt.Completed.class, coordinator.execute(request, () -> false));

        verify(session).prepareOriginal(any(), any(), eq(image), eq(working), any());
        verify(session).publishOriginal(any(), any(), eq(prepared), any());
        verify(session).close();
        verify(finite).execute(any(), any(), any(), eq(Optional.of(shared)), any());
        verify(recoverable).execute(any(), any(), any(), eq(Optional.of(shared)), any());
    }

    @Test
    void suspendedActionRetainsSharedOriginalUntilItsDeferredTargetFinishes() throws Exception {
        request = request(allRecoverableModule(), selectedTargets());
        coordinator =
                new ImageActionCoordinator(
                        registry,
                        lifecycle,
                        new SourcePlanningLimits(1_000_000, 1, 8_000_000),
                        handles,
                        sourceProvisioner,
                        finite,
                        recoverable,
                        metrics);
        RasterInputCodec.ActionSourceRevision sourceRevision = stableSourceRevision();
        when(working.sourceRevision()).thenReturn(sourceRevision);
        when(working.sourcePyramid()).thenReturn(sourcePyramid(sourceRevision));
        when(working.eligibleOriginalSource())
                .thenReturn(Optional.of(eligible(1_234, sourcePyramid(sourceRevision))));
        FiniteSourceProvisioner.Session session = mock(FiniteSourceProvisioner.Session.class);
        FiniteSourceProvisioner.PreparedOriginal prepared =
                mock(FiniteSourceProvisioner.PreparedOriginal.class);
        when(sourceProvisioner.open()).thenReturn(session);
        when(session.prepareOriginal(any(), any(), eq(image), eq(working), any()))
                .thenReturn(prepared);
        SealedOriginalSource shared = sharedOriginal();
        when(session.publishOriginal(any(), any(), eq(prepared), any())).thenReturn(shared);
        LargeActionHandle first =
                handle("50000000-0000-4000-8000-000000000041", LargeActionPhase.EXECUTING);
        AtomicInteger starts = new AtomicInteger();
        when(recoverable.execute(any(), any(), any(), eq(Optional.of(shared)), any()))
                .thenAnswer(
                        invocation -> {
                            if (starts.getAndIncrement() == 0) {
                                ImageActionRegistry.Reservation reservation =
                                        invocation.getArgument(1);
                                ImageActionSubsetPlanner.Subset subset = invocation.getArgument(2);
                                RecoverableActionAttempt.Suspended target =
                                        new RecoverableActionAttempt.Suspended(
                                                first,
                                                subset.execution(),
                                                List.of(),
                                                new IOException("model run response was lost"),
                                                handles,
                                                reservation);
                                return new RecoverableImageActionExecutor.CoordinatedAttempt(
                                        target);
                            }
                            return new RecoverableImageActionExecutor.CoordinatedAttempt(
                                    new RecoverableActionAttempt.Completed(successful()));
                        });
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())));

        ImageActionAttempt.Suspended suspended =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));

        verify(session, never()).close();
        verify(recoverable, times(1)).execute(any(), any(), any(), eq(Optional.of(shared)), any());

        assertInstanceOf(
                ImageActionAttempt.Completed.class, coordinator.resume(suspended, () -> false));

        verify(recoverable, times(2)).execute(any(), any(), any(), eq(Optional.of(shared)), any());
        verify(session, times(1)).close();
        assertReservationAvailable();
    }

    @Test
    void mixedTargetAggregationDefersAndRunsImageMetricsExactlyOnce() throws Exception {
        ModuleSnapshot module = allRecoverableMetricModule();
        ActionSelectionCodec.ImageAction action =
                RecipeExecutorTestData.allModels(
                        false, Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
        request =
                new ImageActionExecutor.Request(
                        RecipeExecutorTestData.ACTION_ID,
                        RecipeExecutorTestData.clientBuild(1_000_000),
                        entry,
                        module,
                        action);
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())));
        when(metrics.execute(
                        argThat(value -> value.action() instanceof ActionSelectionCodec.Metrics),
                        any(),
                        any(),
                        any()))
                .thenReturn(successful());

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                completed.result().outcome());
        verify(recoverable, times(2)).execute(any(), any(), any(), eq(Optional.empty()), any());
        verify(metrics, times(1))
                .execute(
                        argThat(value -> value.action() instanceof ActionSelectionCodec.Metrics),
                        any(),
                        any(),
                        any());
    }

    @Test
    void finalMetricsReceiveOnlyTheRegionWhoseModelTransactionFailed() throws Exception {
        ModuleSnapshot module = allRecoverableMetricModule();
        ActionSelectionCodec.ImageAction action =
                RecipeExecutorTestData.allModels(
                        false, Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
        request = request(module, action);
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(successful())),
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(failed())));
        when(metrics.execute(any(), any(), any(), any())).thenReturn(successful());

        ImageActionAttempt.Completed completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        verify(metrics)
                .execute(
                        any(),
                        any(),
                        argThat(
                                failures ->
                                        failures.keySet()
                                                .equals(
                                                        Set.of(
                                                                new ExecutionScopeCodec.Roi(
                                                                        RecipeExecutorTestData
                                                                                .ROI_B)))),
                        any());
    }

    @ParameterizedTest
    @EnumSource(
            value = RunResults.FailureType.class,
            names = {"METRICS", "CANCELLED", "PERSISTENCE"})
    void savedFiniteModelsSurviveFailureOrCancellationOfTheLaterMetricsPhase(
            RunResults.FailureType failureType) throws Exception {
        request =
                request(
                        RecipeExecutorTestData.fieldMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        when(finite.execute(any(), any(), any(), any(), any())).thenReturn(saved());
        when(metrics.execute(any(), any(), any(), any()))
                .thenReturn(
                        ImageActionAggregation.imageFailure(
                                RecipeExecutorTestData.IMAGE_ID,
                                failureType,
                                new IOException("injected metric phase failure"),
                                List.of()));

        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        assertEquals(failureType, completed.result().imageFailures().getFirst().type());
        var order = inOrder(finite, metrics);
        order.verify(finite).execute(any(), any(), any(), any(), any());
        order.verify(metrics).execute(any(), any(), eq(Map.of()), any());
        verifyNoInteractions(recoverable);
    }

    @Test
    void singleRecoverableResumeCommitsThenRunsMetricsWithoutRepausing() throws Exception {
        request =
                request(
                        allRecoverableMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        var retained = handle("50000000-0000-4000-8000-000000000077", LargeActionPhase.EXECUTING);
        stubSuspensions(retained);
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(saved())));
        when(metrics.execute(any(), any(), any(), any()))
                .thenReturn(
                        ImageActionAggregation.imageFailure(
                                RecipeExecutorTestData.IMAGE_ID,
                                RunResults.FailureType.METRICS,
                                new IOException("metrics script failed"),
                                List.of()));

        var paused =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));
        assertEquals("download interrupted", paused.failure().orElseThrow().getMessage());
        verifyNoInteractions(metrics);
        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.resume(paused, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        var order = inOrder(recoverable, metrics);
        order.verify(recoverable).execute(any(), any(), any(), any(), any());
        order.verify(recoverable)
                .resume(any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any());
        order.verify(metrics).execute(any(), any(), eq(Map.of()), any());
        verify(recoverable, times(1)).execute(any(), any(), any(), any(), any());
        assertReservationAvailable();
    }

    @Test
    void resumeFailureReplacesTheDisplayedCauseAndKeepsItsReservation() throws Exception {
        request =
                request(
                        allRecoverableModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        stubSuspensions(handle("50000000-0000-4000-8000-000000000078", LargeActionPhase.EXECUTING));
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenThrow(new IOException("cannot read retained result"))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(saved())));

        var first =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));
        var second =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class, coordinator.resume(first, () -> false));
        assertEquals("cannot read retained result", second.failure().orElseThrow().getMessage());
        assertReservationBlocked();
        assertInstanceOf(
                ImageActionAttempt.Completed.class, coordinator.resume(second, () -> false));
        assertReservationAvailable();
    }

    @Test
    void viewerRefreshFailurePreventsAnAutomaticMetricsReopenOfTheStaleViewer() throws Exception {
        request =
                request(
                        RecipeExecutorTestData.fieldMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        var refreshFailure =
                new RunResults.ImageRunResult(
                        request.entry().getID(),
                        RunResults.ImageOutcomeType.PARTIAL,
                        true,
                        List.of(),
                        List.of(),
                        List.of(
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.VIEWER_REFRESH,
                                        "could not refresh viewer")),
                        Optional.empty());
        when(finite.execute(any(), any(), any(), any(), any())).thenReturn(refreshFailure);

        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        verifyNoInteractions(metrics);
    }

    @Test
    void supersededResumeDoesNotRunOldMetricsAgainstNewerResults() throws Exception {
        request =
                request(
                        allRecoverableMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        stubSuspensions(handle("50000000-0000-4000-8000-000000000079", LargeActionPhase.EXECUTING));
        when(recoverable.resume(
                        any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                .thenReturn(
                        new RecoverableImageActionExecutor.CoordinatedAttempt(
                                new RecoverableActionAttempt.Completed(saved(), false)));
        var paused =
                assertInstanceOf(
                        ImageActionAttempt.Suspended.class,
                        coordinator.execute(request, () -> false));

        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.resume(paused, () -> false));

        assertEquals(RunResults.ImageOutcomeType.SUCCEEDED, completed.result().outcome());
        verifyNoInteractions(metrics);
        assertReservationAvailable();
    }

    @Test
    void failedViewerRefreshAfterFiniteSaveDoesNotStartTheRecoverableTarget() throws Exception {
        var refreshFailure =
                new RunResults.ImageRunResult(
                        RecipeExecutorTestData.IMAGE_ID,
                        RunResults.ImageOutcomeType.PARTIAL,
                        true,
                        List.of(),
                        List.of(),
                        List.of(
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.VIEWER_REFRESH,
                                        "could not refresh viewer")),
                        Optional.empty());
        when(finite.execute(any(), any(), any(), any(), any())).thenReturn(refreshFailure);

        var completed =
                assertInstanceOf(
                        ImageActionAttempt.Completed.class,
                        coordinator.execute(request, () -> false));

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        assertEquals(
                RunResults.FailureType.REVISION_CONFLICT,
                completed.result().scopes().getFirst().failure().orElseThrow().type());
        verifyNoInteractions(recoverable, metrics);
        assertReservationAvailable();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsafeRecoveredSaveStopsDeferredTargetsAndMetrics(boolean resumed) throws Exception {
        request =
                request(
                        allRecoverableMetricModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(
                                        RecipeExecutorTestData.ROI_A,
                                        RecipeExecutorTestData.ROI_B)));
        var unsafeCompletion =
                new RecoverableImageActionExecutor.CoordinatedAttempt(
                        new RecoverableActionAttempt.Completed(saved(), false));
        ImageActionAttempt attempt;
        if (resumed) {
            stubSuspensions(
                    handle("50000000-0000-4000-8000-000000000080", LargeActionPhase.RESULT_READY));
            when(recoverable.resume(
                            any(RecoverableActionAttempt.ResumeOwnership.class), eq(entry), any()))
                    .thenReturn(unsafeCompletion);
            var paused =
                    assertInstanceOf(
                            ImageActionAttempt.Suspended.class,
                            coordinator.execute(request, () -> false));
            attempt = coordinator.resume(paused, () -> false);
        } else {
            when(recoverable.execute(any(), any(), any(), any(), any()))
                    .thenReturn(unsafeCompletion);
            attempt = coordinator.execute(request, () -> false);
        }

        var completed = assertInstanceOf(ImageActionAttempt.Completed.class, attempt);
        assertEquals(RunResults.ImageOutcomeType.PARTIAL, completed.result().outcome());
        assertEquals(true, completed.result().hierarchySaved());
        assertEquals(
                RunResults.FailureType.REVISION_CONFLICT,
                completed.result().scopes().getFirst().failure().orElseThrow().type());
        verify(recoverable, times(1)).execute(any(), any(), any(), any(), any());
        verifyNoInteractions(metrics);
        assertReservationAvailable();
    }

    private static RunResults.ImageRunResult saved() {
        return new RunResults.ImageRunResult(
                RecipeExecutorTestData.IMAGE_ID,
                RunResults.ImageOutcomeType.SUCCEEDED,
                true,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private static RunResults.ImageRunResult successful() {
        return new RunResults.ImageRunResult(
                RecipeExecutorTestData.IMAGE_ID,
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                false,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private static RunResults.ImageRunResult failed() {
        return new RunResults.ImageRunResult(
                RecipeExecutorTestData.IMAGE_ID,
                RunResults.ImageOutcomeType.FAILED,
                false,
                List.of(),
                List.of(),
                List.of(
                        new RunResults.FailureDetail(
                                RunResults.FailureType.BACKEND, "model transaction failed")),
                Optional.empty());
    }

    private SharedOriginalFixture configureSharedOriginalCapture() throws Exception {
        coordinator =
                new ImageActionCoordinator(
                        registry,
                        lifecycle,
                        new SourcePlanningLimits(1_000_000, 1, 8_000_000),
                        handles,
                        sourceProvisioner,
                        finite,
                        recoverable,
                        metrics);
        RasterInputCodec.ActionSourceRevision sourceRevision = stableSourceRevision();
        when(working.sourceRevision()).thenReturn(sourceRevision);
        when(working.sourcePyramid()).thenReturn(sourcePyramid(sourceRevision));
        when(working.eligibleOriginalSource())
                .thenReturn(Optional.of(eligible(1_234, sourcePyramid(sourceRevision))));
        FiniteSourceProvisioner.Session session = mock(FiniteSourceProvisioner.Session.class);
        FiniteSourceProvisioner.PreparedOriginal prepared =
                mock(FiniteSourceProvisioner.PreparedOriginal.class);
        when(sourceProvisioner.open()).thenReturn(session);
        when(session.prepareOriginal(any(), any(), eq(image), eq(working), any()))
                .thenReturn(prepared);
        return new SharedOriginalFixture(session, prepared);
    }

    private record SharedOriginalFixture(
            FiniteSourceProvisioner.Session session,
            FiniteSourceProvisioner.PreparedOriginal prepared) {}

    private ImageActionExecutor.Request request(
            ModuleSnapshot module, ActionSelectionCodec.ImageAction action) {
        return new ImageActionExecutor.Request(
                RecipeExecutorTestData.ACTION_ID,
                RecipeExecutorTestData.clientBuild(1_000_000),
                entry,
                module,
                action);
    }

    private LargeActionHandle handle(String generationId, LargeActionPhase phase) {
        LargeActionHandle handle = mock(LargeActionHandle.class);
        when(handle.actionId()).thenReturn(request.actionId());
        when(handle.handleGenerationId()).thenReturn(generationId);
        when(handle.imageId()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
        when(handle.phase()).thenReturn(phase);
        return handle;
    }

    private void stubSuspensions(LargeActionHandle... targetHandles) throws Exception {
        AtomicInteger index = new AtomicInteger();
        when(recoverable.execute(any(), any(), any(), eq(Optional.empty()), any()))
                .thenAnswer(
                        invocation -> {
                            ImageActionRegistry.Reservation reservation = invocation.getArgument(1);
                            ImageActionSubsetPlanner.Subset subset = invocation.getArgument(2);
                            LargeActionHandle handle = targetHandles[index.getAndIncrement()];
                            RecoverableActionAttempt.Suspended suspended =
                                    mock(RecoverableActionAttempt.Suspended.class);
                            RecoverableActionAttempt.ResumeOwnership ownership =
                                    new RecoverableActionAttempt.ResumeOwnership(
                                            handle,
                                            Optional.of(subset.execution()),
                                            List.of(),
                                            reservation,
                                            Optional.of(new IOException("download interrupted")));
                            when(suspended.claimForResume()).thenReturn(ownership);
                            return new RecoverableImageActionExecutor.CoordinatedAttempt(suspended);
                        });
    }

    private void assertReservationBlocked() throws Exception {
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var competing =
                    waiter.submit(
                            () -> {
                                try (var ignored =
                                        registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
                                    return true;
                                }
                            });
            assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
            competing.cancel(true);
        }
    }

    private void assertReservationAvailable() throws Exception {
        try (var ignored = registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
            assertEquals(RecipeExecutorTestData.IMAGE_ID, ignored.imageId());
        }
    }

    private SealedOriginalSource sharedOriginal() {
        String artifactId = "40000000-0000-4000-8000-000000000099";
        var source = stableSourceRevision().pixelSource();
        SourceArtifactCodec.SourceArtifactStartRequest start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        request.actionId(),
                        artifactId,
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                        1_234,
                        1_024,
                        new SourceArtifactCodec.OriginalWsi(
                                source,
                                0,
                                new qupath.ext.histoforge.contract.SourceIdentityCodec
                                        .ScaleTranslate2D(1, 1, 0, 0),
                                "slide.tif",
                                readerSettings(source, 1),
                                List.of()),
                        List.of(
                                new SourceArtifactCodec.SourceLevelRequirement(
                                        0, 0, source.width(), source.height())));
        SourceArtifactRef artifact =
                new SourceArtifactRef(
                        artifactId,
                        request.actionId(),
                        SourceArtifactCodec.startRequestDigest(start),
                        "a".repeat(64),
                        "b".repeat(64),
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE);
        return new SealedOriginalSource(
                request.actionId(),
                request.entry().getID(),
                request.clientBuild(),
                request.module().deploymentId(),
                request.module().module(),
                source,
                artifact,
                start);
    }

    private static RasterInputCodec.ActionSourceRevision stableSourceRevision() {
        RasterInputCodec.ActionSourceRevision source = RecipeExecutorTestData.sourceRevision();
        SourceIdentityCodec.ImageSourceIdentity pixelSource = source.pixelSource();
        SourceIdentityCodec.ImageSourceIdentity versioned =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        pixelSource.credentialFreeDescriptorDigest(),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(1_234, 10_000, Optional.empty())),
                        pixelSource.width(),
                        pixelSource.height(),
                        pixelSource.zSlices(),
                        pixelSource.timepoints(),
                        pixelSource.calibration());
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        source.imageDataRevision().imageId(),
                        SourceIdentityCodec.imageSourceIdentityDigest(versioned),
                        source.imageDataRevision().imageDataContentDigest()),
                versioned);
    }

    private static CapturedSourcePyramid sourcePyramid(
            RasterInputCodec.ActionSourceRevision sourceRevision) {
        SourceIdentityCodec.ScaleTranslate2D identity =
                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new CapturedSourcePyramid(
                sourceRevision,
                0,
                identity,
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(
                                        sourceRevision.pixelSource().width(),
                                        sourceRevision.pixelSource().height()),
                                identity)));
    }

    private static ActionSelectionCodec.ImageAction selectedTargets() {
        return new ActionSelectionCodec.Fill(
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.RoiRegions(
                                Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)),
                        List.of(
                                new ActionSelectionCodec.StepTargets(
                                        "parent",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(
                                                        new ActionSelectionCodec.RoiTarget(
                                                                RecipeExecutorTestData.ROI_A)))),
                                new ActionSelectionCodec.StepTargets(
                                        "second",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(
                                                        new ActionSelectionCodec.RoiTarget(
                                                                RecipeExecutorTestData.ROI_B)))))),
                true);
    }

    private static ModuleSnapshot splitProtocolModule() {
        ModuleSnapshot durable =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.twoFieldModule());
        Map<String, ModelSnapshot> models = new LinkedHashMap<>(durable.activatedModels());
        ModelSnapshot second = models.get("second");
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = second.capabilities();
        RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
        ExecutionLimitsCodec.TargetRasterLimits small =
                new ExecutionLimitsCodec.TargetRasterLimits(20, 20, 400);
        RunnerCapabilitiesCodec.RunnerCapabilities split =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        capabilities.modes(),
                        capabilities.outputTypes(),
                        capabilities.sourceContainers(),
                        capabilities.runnerBuild(),
                        capabilities.rasterizer(),
                        capabilities.preprocessingContractDigest(),
                        new RunnerCapabilitiesCodec.FiniteCallLimits(
                                finite.maxAttemptExecutionSeconds(),
                                finite.maxRequestCanonicalBytes(),
                                small,
                                finite.maxInclusionMaskVertices(),
                                finite.result()),
                        capabilities.durableRun());
        models.put(
                "second",
                new ModelSnapshot(
                        second.architecture(),
                        second.gpu(),
                        second.modelConfigDigest(),
                        second.input(),
                        second.output(),
                        split,
                        Optional.of(testLargeRegionProfile()),
                        second.artifactDigests()));
        return new ModuleSnapshot(
                durable.deploymentId(),
                durable.module(),
                durable.exactRecipe(),
                durable.scriptSetDigest(),
                models,
                durable.scripts(),
                durable.metrics());
    }

    private static ModuleSnapshot allRecoverableMetricModule() {
        ModuleSnapshot durable =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldMetricModule());
        ModelSnapshot parent = durable.activatedModels().get("parent");
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = parent.capabilities();
        RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
        RunnerCapabilitiesCodec.RunnerCapabilities split =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        capabilities.modes(),
                        capabilities.outputTypes(),
                        capabilities.sourceContainers(),
                        capabilities.runnerBuild(),
                        capabilities.rasterizer(),
                        capabilities.preprocessingContractDigest(),
                        new RunnerCapabilitiesCodec.FiniteCallLimits(
                                finite.maxAttemptExecutionSeconds(),
                                finite.maxRequestCanonicalBytes(),
                                new ExecutionLimitsCodec.TargetRasterLimits(20, 20, 400),
                                finite.maxInclusionMaskVertices(),
                                finite.result()),
                        capabilities.durableRun());
        return new ModuleSnapshot(
                durable.deploymentId(),
                durable.module(),
                durable.exactRecipe(),
                durable.scriptSetDigest(),
                Map.of(
                        "parent",
                        new ModelSnapshot(
                                parent.architecture(),
                                parent.gpu(),
                                parent.modelConfigDigest(),
                                parent.input(),
                                parent.output(),
                                split,
                                Optional.of(testLargeRegionProfile()),
                                parent.artifactDigests())),
                durable.scripts(),
                durable.metrics());
    }

    private static ModuleSnapshot allRecoverableModule() {
        ModuleSnapshot durable =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.twoFieldModule());
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        durable.activatedModels()
                .forEach((modelId, model) -> models.put(modelId, withSmallFiniteRaster(model)));
        return new ModuleSnapshot(
                durable.deploymentId(),
                durable.module(),
                durable.exactRecipe(),
                durable.scriptSetDigest(),
                models,
                durable.scripts(),
                durable.metrics());
    }

    private static ModelSnapshot withSmallFiniteRaster(ModelSnapshot model) {
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = model.capabilities();
        RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
        RunnerCapabilitiesCodec.RunnerCapabilities split =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        capabilities.modes(),
                        capabilities.outputTypes(),
                        capabilities.sourceContainers(),
                        capabilities.runnerBuild(),
                        capabilities.rasterizer(),
                        capabilities.preprocessingContractDigest(),
                        new RunnerCapabilitiesCodec.FiniteCallLimits(
                                finite.maxAttemptExecutionSeconds(),
                                finite.maxRequestCanonicalBytes(),
                                new ExecutionLimitsCodec.TargetRasterLimits(20, 20, 400),
                                finite.maxInclusionMaskVertices(),
                                finite.result()),
                        capabilities.durableRun());
        return new ModelSnapshot(
                model.architecture(),
                model.gpu(),
                model.modelConfigDigest(),
                model.input(),
                model.output(),
                split,
                Optional.of(testLargeRegionProfile()),
                model.artifactDigests());
    }

    /** The small finite caps select recoverable execution, which requires an advertised profile. */
    private static LargeRegionProfileCodec.GlobalDecodeProfile testLargeRegionProfile() {
        JsonObject preimage = new JsonObject();
        preimage.addProperty("type", "global-decode");
        preimage.addProperty("validation_evidence_digest", "e".repeat(64));
        return new LargeRegionProfileCodec.GlobalDecodeProfile(
                CanonicalCodec.canonicalDigest(preimage), "e".repeat(64));
    }
}
