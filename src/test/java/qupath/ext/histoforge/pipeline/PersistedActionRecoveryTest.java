package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.lib.projects.ProjectImageEntry;

class PersistedActionRecoveryTest {

    private static final String ACTION = "11111111-1111-4111-8111-111111111111";
    private static final String GENERATION = "22222222-2222-4222-8222-222222222222";

    private LargeActionHandleStore handles;
    private RecoverableActionRecovery executor;
    private RecoverableImageActionExecutor actionExecutor;
    private ImageMetricsExecutor metrics;
    private PersistedActionRecovery recovery;
    private LargeActionHandle unresolved;
    private ModuleContentIdentity moduleIdentity;

    @BeforeEach
    void setUp() {
        handles = mock(LargeActionHandleStore.class);
        executor = mock(RecoverableActionRecovery.class);
        actionExecutor = mock(RecoverableImageActionExecutor.class);
        when(actionExecutor.recovery()).thenReturn(executor);
        metrics = mock(ImageMetricsExecutor.class);
        recovery = new PersistedActionRecovery(handles, actionExecutor, metrics);
        moduleIdentity = mock(ModuleContentIdentity.class);
        when(moduleIdentity.moduleRef()).thenReturn(new ModuleRef("histoforge", "module"));
        unresolved = handle(LargeActionPhase.EXECUTING);
    }

    @Test
    void constructionInspectionAndCloseDoNotOpenCloudServices(@TempDir Path root) throws Exception {
        var build = RecipeExecutorTestData.clientBuild(1_000_000);
        var store = new LargeActionHandleStore(root, build);
        @SuppressWarnings("unchecked")
        Supplier<GatewayTransport> connection = mock(Supplier.class);
        try (var local =
                new PersistedActionRecovery(
                        store,
                        new ImageActionRegistry(),
                        mock(ImageLifecycle.class),
                        build,
                        connection,
                        "deployment",
                        1,
                        mock(MetricsEngine.class),
                        ignored -> {},
                        ignored -> {})) {
            assertTrue(local.unresolved().isEmpty());
        }
        verifyNoInteractions(connection);
    }

    @Test
    void listsOnlyNonterminalHandlesWithoutDispatching() throws Exception {
        LargeActionHandle committed = handle(LargeActionPhase.COMMITTED);
        when(handles.list()).thenReturn(List.of(unresolved, committed));

        List<PersistedActionRecovery.Item> items = recovery.unresolved();

        assertEquals(1, items.size());
        assertEquals(ACTION, items.getFirst().actionId());
        assertEquals("histoforge/module", items.getFirst().moduleRef());
        assertTrue(items.getFirst().wholeSlide());
        verify(handles).list();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadsTheExactItemBeforeExplicitResume() throws Exception {
        PersistedActionRecovery.Item item = item();
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        RunResults.ImageRunResult result = result();
        when(handles.load(ACTION, GENERATION)).thenReturn(unresolved);
        when(executor.resume(
                        eq(ACTION),
                        eq(GENERATION),
                        eq(entry),
                        any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new RecoverableActionAttempt.Completed(result));

        PersistedActionRecovery.Completed completed =
                assertInstanceOf(
                        PersistedActionRecovery.Completed.class,
                        recovery.resume(item, entry, () -> false));

        assertEquals(result, completed.result());
        verify(handles).load(ACTION, GENERATION);
    }

    @Test
    void rejectsAStaleProjectionBeforeResumeOrAbandonment() throws Exception {
        PersistedActionRecovery.Item item = item();
        LargeActionHandle uploading = handle(LargeActionPhase.UPLOADING);
        when(handles.load(ACTION, GENERATION)).thenReturn(uploading);

        assertThrows(
                IllegalStateException.class,
                () -> recovery.resume(item, mock(ProjectImageEntry.class), () -> false));
        assertThrows(IllegalStateException.class, () -> recovery.abandon(item));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pausedAttemptCanBeRetriedOrDurablyAbandonedOnlyByItsOwner() throws Exception {
        PersistedActionRecovery.Item item = item();
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        RecoverableActionAttempt.Suspended suspended =
                mock(RecoverableActionAttempt.Suspended.class);
        when(handles.load(ACTION, GENERATION)).thenReturn(unresolved);
        when(suspended.lastObservedHandle()).thenReturn(unresolved);
        when(suspended.failure()).thenReturn(new java.io.IOException("network unavailable"));
        when(executor.resume(
                        eq(ACTION),
                        eq(GENERATION),
                        eq(entry),
                        any(java.util.function.BooleanSupplier.class)))
                .thenReturn(suspended);
        when(executor.resume(
                        eq(suspended), eq(entry), any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new RecoverableActionAttempt.Completed(result()));

        PersistedActionRecovery.Paused paused =
                assertInstanceOf(
                        PersistedActionRecovery.Paused.class,
                        recovery.resume(item, entry, () -> false));
        assertEquals("network unavailable", paused.failureMessage());
        assertInstanceOf(
                PersistedActionRecovery.Completed.class,
                recovery.resume(paused, entry, () -> false));

        PersistedActionRecovery other =
                new PersistedActionRecovery(handles, actionExecutor, metrics);
        assertThrows(IllegalArgumentException.class, () -> other.abandon(paused));
    }

    @Test
    void explicitAbandonmentDoesNotRequireRunningBuildEquality() throws Exception {
        PersistedActionRecovery.Item item = item();
        when(handles.load(ACTION, GENERATION)).thenReturn(unresolved);

        recovery.abandon(item);

        verify(handles).abandon(ACTION, GENERATION);
    }

    @Test
    void completedModelRecoveryRunsMetricsAfterwardWithoutSecondModelExecution() throws Exception {
        assertMetricsAfterModelRecovery(false);
    }

    @Test
    void metricFailurePreservesSavedModelsAndReturnsPartialWithoutSecondModelExecution()
            throws Exception {
        assertMetricsAfterModelRecovery(true);
    }

    @SuppressWarnings("unchecked")
    private void assertMetricsAfterModelRecovery(boolean failMetrics) throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        ModuleSnapshot module = RecipeExecutorTestData.fieldMetricModule();
        when(unresolved.module()).thenReturn(module);
        when(unresolved.action())
                .thenReturn(
                        new ActionSelectionCodec.Fill(
                                new ActionSelectionCodec.AllModels(
                                        new ActionSelectionCodec.SlideRegion(
                                                new ActionSelectionCodec.FullSlide())),
                                true));
        when(handles.list()).thenReturn(List.of(unresolved));
        when(handles.load(ACTION, GENERATION)).thenReturn(unresolved);
        RunResults.ImageRunResult models =
                new RunResults.ImageRunResult(
                        "image",
                        RunResults.ImageOutcomeType.SUCCEEDED,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.empty());
        when(executor.resume(eq(ACTION), eq(GENERATION), eq(entry), any()))
                .thenReturn(new RecoverableActionAttempt.Completed(models));
        RunResults.FailureDetail failure =
                new RunResults.FailureDetail(RunResults.FailureType.METRICS, "metric failed");
        MetricTypes.MetricImageResult evaluation =
                new MetricTypes.MetricImageResult(
                        "a".repeat(64),
                        new RasterInputCodec.ImageRevisionToken(
                                "image", "b".repeat(64), "c".repeat(64)),
                        Map.of(
                                new ExecutionScopeCodec.Slide(),
                                new MetricTypes.MetricRegionResult(
                                        Map.of("count", new MetricTypes.Scalar(1)))),
                        Map.of());
        RunResults.ImageRunResult metricResult =
                failMetrics
                        ? new RunResults.ImageRunResult(
                                "image",
                                RunResults.ImageOutcomeType.FAILED,
                                false,
                                List.of(),
                                List.of(),
                                List.of(failure),
                                Optional.empty())
                        : new RunResults.ImageRunResult(
                                "image",
                                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                                false,
                                List.of(),
                                List.of(),
                                List.of(),
                                Optional.of(evaluation));
        when(metrics.execute(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            ImageActionExecutor.Request request = invocation.getArgument(0);
                            assertEquals(
                                    new ActionSelectionCodec.Metrics(
                                            new ActionSelectionCodec.SlideRegion(
                                                    new ActionSelectionCodec.FullSlide())),
                                    request.action());
                            assertEquals(ACTION, request.actionId());
                            assertEquals(entry, request.entry());
                            assertEquals(module.metrics(), request.module().metrics());
                            assertEquals(unresolved.selectionRegions(), invocation.getArgument(2));
                            return metricResult;
                        });

        PersistedActionRecovery.Completed completed =
                assertInstanceOf(
                        PersistedActionRecovery.Completed.class,
                        recovery.resume(recovery.unresolved().getFirst(), entry, () -> false));

        assertEquals(
                failMetrics
                        ? RunResults.ImageOutcomeType.PARTIAL
                        : RunResults.ImageOutcomeType.SUCCEEDED,
                completed.result().outcome());
        assertTrue(completed.result().hierarchySaved());
        assertEquals(
                failMetrics ? Optional.empty() : Optional.of(evaluation),
                completed.result().metrics());
        assertEquals(
                failMetrics ? List.of(failure) : List.of(), completed.result().imageFailures());
        var order = inOrder(executor, metrics);
        order.verify(executor).resume(eq(ACTION), eq(GENERATION), eq(entry), any());
        order.verify(metrics).execute(any(), any(), any());
        verifyNoMoreInteractions(executor, metrics);
    }

    @Test
    void supersededCompletionDoesNotRunMetricsAgainstNewerHierarchy() throws Exception {
        assertMetricsSkipped(new RecoverableActionAttempt.Completed(result(), false));
    }

    @Test
    void viewerRefreshFailureDoesNotOpenMetricsAndFlushStaleViewerOverSavedModels()
            throws Exception {
        RunResults.ImageRunResult models =
                new RunResults.ImageRunResult(
                        "image",
                        RunResults.ImageOutcomeType.PARTIAL,
                        true,
                        List.of(),
                        List.of(),
                        List.of(
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.VIEWER_REFRESH,
                                        "viewer refresh failed")),
                        Optional.empty());
        assertMetricsSkipped(new RecoverableActionAttempt.Completed(models));
    }

    @SuppressWarnings("unchecked")
    private void assertMetricsSkipped(RecoverableActionAttempt.Completed models) throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(unresolved.module()).thenReturn(RecipeExecutorTestData.fieldMetricModule());
        when(handles.list()).thenReturn(List.of(unresolved));
        when(handles.load(ACTION, GENERATION)).thenReturn(unresolved);
        when(executor.resume(eq(ACTION), eq(GENERATION), eq(entry), any())).thenReturn(models);

        PersistedActionRecovery.Completed completed =
                assertInstanceOf(
                        PersistedActionRecovery.Completed.class,
                        recovery.resume(recovery.unresolved().getFirst(), entry, () -> false));

        assertEquals(models.result(), completed.result());
        verifyNoInteractions(metrics);
    }

    private PersistedActionRecovery.Item item() {
        return new PersistedActionRecovery.Item(
                ACTION,
                GENERATION,
                "image",
                "histoforge/module",
                moduleIdentity,
                "deployment",
                RecipeExecutorTestData.clientBuild(1_000_000),
                true,
                LargeActionPhase.EXECUTING,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private LargeActionHandle handle(LargeActionPhase phase) {
        LargeActionHandle handle = mock(LargeActionHandle.class);
        ModuleSnapshot module = mock(ModuleSnapshot.class);
        when(handle.actionId()).thenReturn(ACTION);
        when(handle.handleGenerationId()).thenReturn(GENERATION);
        when(handle.imageId()).thenReturn("image");
        when(handle.module()).thenReturn(module);
        when(handle.clientBuild()).thenReturn(RecipeExecutorTestData.clientBuild(1_000_000));
        when(handle.phase()).thenReturn(phase);
        when(handle.createdAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        ExecutionScopeCodec.TargetRegion target = mock(ExecutionScopeCodec.TargetRegion.class);
        when(target.key()).thenReturn(new ExecutionScopeCodec.Slide());
        when(handle.target()).thenReturn(target);
        when(module.module()).thenReturn(moduleIdentity);
        when(module.deploymentId()).thenReturn("deployment");
        return handle;
    }

    private static RunResults.ImageRunResult result() {
        return new RunResults.ImageRunResult(
                "image",
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                false,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }
}
